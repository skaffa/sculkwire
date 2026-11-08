package dev.sculkwire.core.dispatch;

import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.notify.HttpNotifier;
import dev.sculkwire.core.notify.HttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.Notifier;
import dev.sculkwire.core.notify.NotifierRegistry;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.notify.SecretSource;
import dev.sculkwire.core.notify.SendResult;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.util.Clock;
import dev.sculkwire.core.util.Json;
import dev.sculkwire.core.util.Redact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Delivers notifications off the game thread. One lane per notifier keeps order and a per-notifier rate
 * limit; failed sends are retried (I/O errors and 5xx: 2 s, 8 s, 30 s; 429: after Retry-After, max 60 s);
 * other 4xx fail at once. A bounded queue drops the oldest when full.
 */
public final class Dispatcher implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("Sculkwire");
	public static final int MAX_QUEUE = 200;
	static final long[] BACKOFF_MS = {2_000, 8_000, 30_000};
	static final int MAX_ATTEMPTS = 1 + BACKOFF_MS.length;

	/** Per-notifier delivery statistics. */
	public static final class Stats {
		public volatile int sent;
		public volatile int failed;
		public volatile long lastSuccessAt;
		public volatile long lastErrorAt;
		public volatile String lastError = "";
		public volatile int consecutiveFailures;
	}

	private static final class Job {
		final String notifierId;
		final Notification n;
		final CompletableFuture<History.Entry> result;
		final long queuedAt;
		int attempt = 1;

		Job(String notifierId, Notification n, CompletableFuture<History.Entry> result, long queuedAt) {
			this.notifierId = notifierId;
			this.n = n;
			this.result = result;
			this.queuedAt = queuedAt;
		}
	}

	private static final class Lane {
		final ArrayDeque<Job> queue = new ArrayDeque<>();
		boolean inFlight;
		long nextAllowedAt;
		boolean wakeScheduled;
	}

	private record Cached(String fingerprint, Notifier notifier) {
	}

	private final NotifierRegistry types;
	private final Supplier<SculkwireConfig> config;
	private final SecretSource secrets;
	private final Supplier<Set<String>> secretValues;
	private final HttpTransport http;
	private final History history;
	private final Clock clock;
	private final ScheduledExecutorService exec;
	private final Map<String, Lane> lanes = new HashMap<>();
	private final Map<String, Cached> instances = new HashMap<>();
	private final Map<String, Stats> stats = new LinkedHashMap<>();
	private volatile Consumer<String> failureWarning = s -> { };
	private int queued;
	private long[] backoff = BACKOFF_MS;
	private long minIntervalOverride = -1;

	public Dispatcher(NotifierRegistry types, Supplier<SculkwireConfig> config, SecretSource secrets,
			Supplier<Set<String>> secretValues, HttpTransport http, History history, Clock clock) {
		this.types = types;
		this.config = config;
		this.secrets = secrets;
		this.secretValues = secretValues == null ? Set::of : secretValues;
		this.http = http;
		this.history = history;
		this.clock = clock;
		this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "sculkwire-dispatch");
			t.setDaemon(true);
			return t;
		});
	}

	/** For tests: shorter retry delays and rate limit. */
	public synchronized void setTiming(long[] backoffMs, long minIntervalMs) {
		this.backoff = backoffMs.clone();
		this.minIntervalOverride = minIntervalMs;
	}

	/** Called (on the dispatch thread) the first time a notifier fails 3 times in a row. */
	public void setFailureWarning(Consumer<String> c) {
		this.failureWarning = c == null ? s -> { } : c;
	}

	public History history() {
		return history;
	}

	public NotifierRegistry types() {
		return types;
	}

	public synchronized Stats stats(String notifierId) {
		return stats.computeIfAbsent(notifierId, k -> new Stats());
	}

	public synchronized int queued() {
		return queued;
	}

	/** Templates in a notifier's own settings (for value capture by the rule engine). */
	public List<Template> templatesFor(String notifierId) {
		NotifierConfig cfg = config.get().notifier(notifierId);
		if (cfg == null) {
			return List.of();
		}
		Notifier n = instance(cfg);
		return n == null ? List.of() : n.templates();
	}

	/** Drops cached notifier instances (call after editing notifiers or secrets). */
	public synchronized void invalidate() {
		instances.clear();
	}

	public CompletableFuture<History.Entry> submit(String notifierId, Notification n) {
		CompletableFuture<History.Entry> result = new CompletableFuture<>();
		Job job = new Job(notifierId, n, result, clock.millis());
		Job dropped = null;
		synchronized (this) {
			if (queued >= MAX_QUEUE) {
				Lane biggest = null;
				for (Lane l : lanes.values()) {
					if (!l.queue.isEmpty() && (biggest == null || l.queue.size() > biggest.queue.size())) {
						biggest = l;
					}
				}
				if (biggest != null) {
					dropped = biggest.queue.pollFirst();
					queued--;
				}
			}
			lanes.computeIfAbsent(notifierId, k -> new Lane()).queue.addLast(job);
			queued++;
		}
		if (dropped != null) {
			finish(dropped, History.Status.DROPPED, 0, "Queue full: dropped", 0);
		}
		exec.execute(this::pump);
		return result;
	}

	/** Waits until the queue is empty (or the timeout passes). Returns true when everything was sent. */
	public boolean flush(long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			synchronized (this) {
				boolean busy = queued > 0 || lanes.values().stream().anyMatch(l -> l.inFlight);
				if (!busy) {
					return true;
				}
			}
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	@Override
	public void close() {
		exec.shutdownNow();
	}

	// ---- dispatch thread ----

	private void pump() {
		long now = clock.millis();
		List<Map.Entry<String, Lane>> snapshot;
		synchronized (this) {
			snapshot = List.copyOf(lanes.entrySet());
		}
		for (Map.Entry<String, Lane> e : snapshot) {
			Lane lane = e.getValue();
			Job job;
			synchronized (this) {
				if (lane.inFlight || lane.queue.isEmpty()) {
					continue;
				}
				if (now < lane.nextAllowedAt) {
					if (!lane.wakeScheduled) {
						lane.wakeScheduled = true;
						long delay = lane.nextAllowedAt - now;
						exec.schedule(() -> {
							synchronized (this) {
								lane.wakeScheduled = false;
							}
							pump();
						}, delay, TimeUnit.MILLISECONDS);
					}
					continue;
				}
				job = lane.queue.pollFirst();
				queued--;
				lane.inFlight = true;
			}
			start(lane, job);
		}
	}

	private void start(Lane lane, Job job) {
		NotifierConfig cfg = config.get().notifier(job.notifierId);
		if (cfg == null || (!cfg.enabled && !job.n.test())) {
			done(lane, job, SendResult.fail(0, cfg == null ? "Notifier was removed" : "Notifier is disabled"), 0);
			return;
		}
		Notifier notifier = instance(cfg);
		if (notifier == null) {
			done(lane, job, SendResult.fail(0, "Unknown notifier type '" + cfg.type + "'"), 0);
			return;
		}
		long started = clock.millis();
		CompletableFuture<SendResult> f;
		try {
			f = notifier.send(job.n);
		} catch (RuntimeException ex) {
			f = CompletableFuture.completedFuture(HttpNotifier.fromException(ex));
		}
		f.whenComplete((res, ex) -> exec.execute(() -> done(lane, job,
				ex != null ? HttpNotifier.fromException(ex) : res, clock.millis() - started)));
	}

	private void done(Lane lane, Job job, SendResult res, long latency) {
		long now = clock.millis();
		NotifierType type = typeOf(job.notifierId);
		long minInterval = minIntervalOverride >= 0 ? minIntervalOverride : type == null ? 500 : type.minIntervalMillis();
		boolean retry = false;
		synchronized (this) {
			lane.inFlight = false;
			lane.nextAllowedAt = Math.max(lane.nextAllowedAt, now + minInterval);
			if (!res.ok() && res.retryable() && job.attempt <= backoff.length) {
				long delay = res.retryAfterMs() > 0 ? Math.min(res.retryAfterMs(), 60_000) : backoff[job.attempt - 1];
				job.attempt++;
				lane.nextAllowedAt = Math.max(lane.nextAllowedAt, now + delay);
				lane.queue.addFirst(job);
				queued++;
				retry = true;
			}
		}
		if (!retry) {
			finish(job, res.ok() ? History.Status.SENT : History.Status.FAILED, res.status(), res.error(), latency);
		}
		pump();
	}

	private void finish(Job job, History.Status status, int http, String error, long latency) {
		NotifierConfig cfg = config.get().notifier(job.notifierId);
		String name = cfg == null ? job.notifierId : cfg.displayName();
		Collection<String> secretSet = secretValues.get();
		String err = Redact.text(error == null ? "" : error, secretSet);
		History.Entry e = new History.Entry(clock.millis(), job.n.ruleId(), job.n.ruleName(), job.notifierId, name,
				HttpNotifier.truncate(job.n.title(OutputContext.PLAIN), 200),
				HttpNotifier.truncate(job.n.body(OutputContext.PLAIN), 500),
				status, http, err, job.attempt, latency, job.n.test());
		Stats s = stats(job.notifierId);
		boolean warn = false;
		synchronized (s) {
			if (status == History.Status.SENT) {
				s.sent++;
				s.lastSuccessAt = e.time();
				s.consecutiveFailures = 0;
			} else {
				s.failed++;
				s.lastErrorAt = e.time();
				s.lastError = err;
				s.consecutiveFailures++;
				warn = s.consecutiveFailures == 3;
			}
		}
		if (status != History.Status.SENT) {
			LOG.warn("Sculkwire: sending to {} failed: {}", name, err);
		}
		history.add(e);
		if (warn) {
			failureWarning.accept(name + ": " + err);
		}
		job.result.complete(e);
	}

	private NotifierType typeOf(String notifierId) {
		NotifierConfig cfg = config.get().notifier(notifierId);
		return cfg == null ? null : types.get(cfg.type);
	}

	private synchronized Notifier instance(NotifierConfig cfg) {
		NotifierType type = types.get(cfg.type);
		if (type == null) {
			return null;
		}
		StringBuilder fp = new StringBuilder(Json.COMPACT.toJson(cfg));
		for (var f : type.fields()) {
			if (f.secret()) {
				fp.append('|').append(secrets.get(cfg.id, f.key()).hashCode());
			}
		}
		String fingerprint = fp.toString();
		Cached c = instances.get(cfg.id);
		if (c == null || !c.fingerprint().equals(fingerprint)) {
			try {
				c = new Cached(fingerprint, type.create(cfg, secrets, http));
			} catch (RuntimeException e) {
				LOG.warn("Sculkwire: could not set up notifier {}", cfg.id, e);
				return null;
			}
			instances.put(cfg.id, c);
		}
		return c.notifier();
	}
}
