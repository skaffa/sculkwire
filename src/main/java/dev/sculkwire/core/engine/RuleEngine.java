package dev.sculkwire.core.engine;

import dev.sculkwire.core.event.EventFilter;
import dev.sculkwire.core.event.EventRegistry;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.Scope;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Settings;
import dev.sculkwire.core.model.Throttle;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.signal.SignalReader;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.template.Snapshot;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.util.Clock;
import dev.sculkwire.core.util.Glob;
import dev.sculkwire.core.util.Json;
import dev.sculkwire.core.value.Ops;
import dev.sculkwire.core.value.TagResolver;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Evaluates rules. Call {@link #tick()} every client tick and {@link #post} for game events, both on the
 * game thread. Fired notifications go to the {@link Sink} with their values already captured.
 */
public final class RuleEngine {
	private static final Logger LOG = LoggerFactory.getLogger("Sculkwire");

	/** Receives fired notifications (one per target notifier). */
	@FunctionalInterface
	public interface Sink {
		void deliver(Delivery delivery);
	}

	public record Delivery(String notifierId, Notification notification) {
	}

	/** What happened the last time a rule triggered, for the UI. */
	public record Status(String outcome, long at, long lastFiredAt) {
	}

	private final SignalRegistry signals;
	private final EventRegistry events;
	private final Clock clock;
	private final Sink sink;
	private final RuleCompiler compiler;
	private final TickCache cache;
	private TagResolver tags = TagResolver.NONE;
	private Function<String, List<Template>> notifierTemplates = id -> List.of();
	private Consumer<String> debug;

	private SculkwireConfig config = new SculkwireConfig();
	private List<CompiledRule> compiled = List.of();
	private Map<String, CompiledRule> byId = Map.of();
	private Map<String, List<CompiledRule>> byEvent = Map.of();
	private final Map<String, RuleState> states = new HashMap<>();
	private final Map<String, String> ruleJson = new HashMap<>();
	private final Map<String, Value> vars = new LinkedHashMap<>();
	private final Set<String> warned = new HashSet<>();
	private long tick;
	private long graceUntil;
	private long conditionGraceUntil;

	public RuleEngine(SignalRegistry signals, EventRegistry events, Clock clock, Sink sink) {
		this.signals = signals;
		this.events = events;
		this.clock = clock;
		this.sink = sink;
		this.compiler = new RuleCompiler(signals, events);
		this.cache = new TickCache(signals);
	}

	public void setTagResolver(TagResolver tags) {
		this.tags = tags == null ? TagResolver.NONE : tags;
	}

	/** Templates used by a notifier's own settings (e.g. a webhook body), so their values get captured. */
	public void setNotifierTemplates(Function<String, List<Template>> f) {
		this.notifierTemplates = f == null ? id -> List.of() : f;
	}

	/** Receives a line for every posted event and rule outcome (for /sculkwire debug). Null turns it off. */
	public void setDebug(Consumer<String> debug) {
		this.debug = debug;
	}

	public RuleCompiler compiler() {
		return compiler;
	}

	public SculkwireConfig config() {
		return config;
	}

	public SignalReader reader() {
		return cache;
	}

	public void load(SculkwireConfig cfg) {
		config = cfg.sanitize();
		vars.clear();
		for (UserVariable v : cfg.variables) {
			vars.put(v.name, v.toValue());
		}
		List<CompiledRule> list = new ArrayList<>();
		Map<String, CompiledRule> ids = new LinkedHashMap<>();
		Map<String, List<CompiledRule>> evIndex = new HashMap<>();
		for (Rule rule : cfg.rules) {
			CompiledRule cr = compiler.compile(rule, cfg);
			list.add(cr);
			ids.put(rule.id, cr);
			String json = Json.COMPACT.toJson(rule);
			if (!json.equals(ruleJson.get(rule.id))) {
				states.put(rule.id, new RuleState());
				ruleJson.put(rule.id, json);
				warned.remove(rule.id);
			}
			if (cr.kind == CompiledRule.Kind.EVENT && cr.event != null) {
				evIndex.computeIfAbsent(cr.event.id(), k -> new ArrayList<>()).add(cr);
			}
		}
		states.keySet().retainAll(ids.keySet());
		ruleJson.keySet().retainAll(ids.keySet());
		compiled = List.copyOf(list);
		byId = ids;
		byEvent = evIndex;
	}

	public List<CompiledRule> rules() {
		return compiled;
	}

	public CompiledRule rule(String id) {
		return byId.get(id);
	}

	public Status status(String ruleId) {
		RuleState st = states.get(ruleId);
		return st == null ? new Status("", 0, RuleState.NEVER) : new Status(st.lastOutcome, st.lastOutcomeAt, st.lastFiredAt);
	}

	public Map<String, Value> variables() {
		return Collections.unmodifiableMap(vars);
	}

	/** Sets (or with NONE removes) a user variable and updates the config object. The caller saves it. */
	public void setVariable(String name, Value v) {
		config.variables.removeIf(u -> u.name.equals(name));
		if (v == null || v.isNone()) {
			vars.remove(name);
		} else {
			config.variables.add(UserVariable.of(name, v));
			vars.put(name, v);
		}
		load(config);
	}

	public long currentTick() {
		return tick;
	}

	// ---- lifecycle ----

	public void onJoin() {
		long now = clock.millis();
		states.values().forEach(RuleState::resetSession);
		cache.clear();
		graceUntil = now + (long) (config.settings.joinGraceSeconds * 1000);
		conditionGraceUntil = graceUntil;
	}

	public void onDisconnect() {
		states.values().forEach(RuleState::resetSession);
		cache.clear();
	}

	public void onDimensionChange() {
		conditionGraceUntil = clock.millis() + (long) (config.settings.joinGraceSeconds * 1000);
	}

	// ---- evaluation ----

	public void tick() {
		tick++;
		cache.newTick(tick);
		long now = clock.millis();
		for (CompiledRule cr : compiled) {
			if (!cr.active() || cr.kind == CompiledRule.Kind.EVENT || tick % cr.interval != 0) {
				continue;
			}
			try {
				evaluate(cr, now);
			} catch (RuntimeException e) {
				if (warned.add(cr.rule.id)) {
					LOG.warn("Sculkwire rule '{}' failed", cr.rule.id, e);
				}
			}
		}
	}

	public void post(String eventId, Map<String, Value> payload) {
		if (debug != null) {
			debug.accept("event " + eventId + " " + payload);
		}
		List<CompiledRule> list = byEvent.get(eventId);
		if (list == null) {
			return;
		}
		long now = clock.millis();
		for (CompiledRule cr : list) {
			if (!cr.active()) {
				continue;
			}
			RuleState st = state(cr);
			EvalContext ctx = context(cr, new LinkedHashMap<>(payload), st);
			try {
				EventFilter f = cr.event.filter();
				if (f != null && !f.test(cr.rule.trigger.params, ctx)) {
					continue;
				}
				consider(cr, ctx, st, now);
			} catch (RuntimeException e) {
				if (warned.add(cr.rule.id)) {
					LOG.warn("Sculkwire rule '{}' failed on {}", cr.rule.id, eventId, e);
				}
			}
		}
	}

	/** Sends a rule's notifications now with current (or sample) values, ignoring throttles. */
	public boolean testFire(String ruleId) {
		CompiledRule cr = byId.get(ruleId);
		if (cr == null) {
			return false;
		}
		RuleState st = state(cr);
		EvalContext ctx = previewContext(cr, st);
		deliver(cr, ctx, true);
		outcome(cr, st, clock.millis(), "test sent");
		return !cr.targets.isEmpty();
	}

	/** A context with sample payload values (or current ones for change triggers), for previews. */
	public EvalContext previewContext(CompiledRule cr) {
		return previewContext(cr, new RuleState());
	}

	private EvalContext previewContext(CompiledRule cr, RuleState st) {
		Map<String, Value> payload = cr.event != null ? cr.event.samplePayload() : new LinkedHashMap<>();
		if (cr.kind == CompiledRule.Kind.CHANGE && cr.change != null) {
			Value v = cache.read(cr.change.id(), cr.change.params());
			payload.put("old", v);
			payload.put("new", v);
		}
		return context(cr, payload, st);
	}

	private void evaluate(CompiledRule cr, long now) {
		RuleState st = state(cr);
		EvalContext ctx = context(cr, new LinkedHashMap<>(), st);
		long grace = cr.kind == CompiledRule.Kind.CONDITION ? Math.max(graceUntil, conditionGraceUntil) : graceUntil;
		boolean prime = (!st.primed || now < grace) && !cr.rule.throttle.fireOnJoin;
		switch (cr.kind) {
			case CONDITION -> evalCondition(cr, st, ctx, now, prime);
			case CHANGE -> evalChange(cr, st, ctx, now, prime);
			case SCHEDULE -> evalSchedule(cr, st, ctx, now, prime);
			default -> {
			}
		}
		st.primed = true;
	}

	private void evalCondition(CompiledRule cr, RuleState st, EvalContext ctx, long now, boolean prime) {
		boolean raw = cr.trigger.test(ctx);
		if (raw) {
			if (st.trueSince < 0) {
				st.trueSince = now;
			}
		} else {
			st.trueSince = -1;
		}
		boolean eff = raw && now - st.trueSince >= (long) (cr.forSeconds * 1000);
		if (prime) {
			st.armed = !eff;
			st.lastEff = eff;
			st.lastRepeat = eff ? now : RuleState.NEVER;
			return;
		}
		boolean fire = false;
		switch (cr.mode) {
			case ON_ENTER -> {
				if (!st.armed) {
					boolean rearm = cr.rearm != null ? cr.rearm.test(ctx) : !eff;
					if (rearm) {
						st.armed = true;
					}
				}
				if (st.armed && eff) {
					fire = true;
					st.armed = false;
				}
			}
			case ON_EXIT -> fire = st.lastEff && !eff;
			case WHILE_TRUE -> {
				if (eff) {
					if (st.lastRepeat == RuleState.NEVER || now - st.lastRepeat >= cr.repeatMs) {
						fire = true;
						st.lastRepeat = now;
					}
				} else {
					st.lastRepeat = RuleState.NEVER;
				}
			}
		}
		st.lastEff = eff;
		if (fire) {
			consider(cr, ctx, st, now);
		}
	}

	private void evalChange(CompiledRule cr, RuleState st, EvalContext ctx, long now, boolean prime) {
		Value v = ctx.signal(cr.change.id(), cr.change.params());
		Value prev = st.prevValue;
		st.prevValue = v;
		if (prime || prev == null || prev.isNone() || v.isNone() || prev.equals(v)) {
			return;
		}
		if (cr.minDelta > 0 && prev.type() == ValueType.NUMBER && v.type() == ValueType.NUMBER
				&& Math.abs(v.asNumber() - prev.asNumber()) < cr.minDelta) {
			st.prevValue = prev; // let small drifts add up
			return;
		}
		if (!matchesFilter(prev, cr.from) || !matchesFilter(v, cr.to)) {
			return;
		}
		ctx.putPayload("old", prev);
		ctx.putPayload("new", v);
		consider(cr, ctx, st, now);
	}

	private boolean matchesFilter(Value v, Value filter) {
		if (filter == null || filter.isNone()) {
			return true;
		}
		if (filter.type() == ValueType.LIST) {
			if (filter.asList().isEmpty()) {
				return true;
			}
			for (String item : filter.asList()) {
				if (Ops.equalsLoose(v, Value.str(item), true, tags)) {
					return true;
				}
			}
			return false;
		}
		if (filter.type() == ValueType.STRING && filter.asString().isBlank()) {
			return true;
		}
		return Ops.equalsLoose(v, filter, true, tags);
	}

	private void evalSchedule(CompiledRule cr, RuleState st, EvalContext ctx, long now, boolean prime) {
		boolean fire = false;
		if (cr.everyMs > 0) {
			if (prime || st.lastScheduled == 0) {
				st.lastScheduled = now;
			} else if (now - st.lastScheduled >= cr.everyMs) {
				st.lastScheduled = now;
				fire = true;
			}
		}
		if (cr.atWorldTime != null) {
			double cur = ctx.signal("world.time_of_day", Map.of()).asNumber();
			double prev = st.prevWorldTime;
			st.prevWorldTime = cur;
			if (!prime && !Double.isNaN(prev) && !Double.isNaN(cur) && crossed(prev, cur, cr.atWorldTime)) {
				fire = true;
			}
		}
		if (cr.atRealTime != null) {
			ZonedDateTime n = clock.now();
			LocalDate today = n.toLocalDate();
			boolean past = !n.toLocalTime().isBefore(cr.atRealTime);
			if (prime) {
				if (past) {
					st.lastDate = today;
				}
			} else if (past && !today.equals(st.lastDate)) {
				st.lastDate = today;
				fire = true;
			}
		}
		if (fire) {
			consider(cr, ctx, st, now);
		}
	}

	static boolean crossed(double prev, double cur, double target) {
		if (cur == prev) {
			return false;
		}
		if (cur > prev) {
			return prev < target && target <= cur;
		}
		return target > prev || target <= cur; // wrapped past midnight (or time was set back)
	}

	private void consider(CompiledRule cr, EvalContext ctx, RuleState st, long now) {
		String why = blockReason(cr, ctx, st, now);
		if (why != null) {
			outcome(cr, st, now, why);
			return;
		}
		st.lastFiredAt = now;
		st.recent.addLast(now);
		st.firedThisSession = true;
		outcome(cr, st, now, "fired");
		deliver(cr, ctx, false);
	}

	private String blockReason(CompiledRule cr, EvalContext ctx, RuleState st, long now) {
		if (cr.conditions != null && !cr.conditions.test(ctx)) {
			return "conditions not met";
		}
		if (!inScope(cr.rule.scope, ctx)) {
			return "out of scope";
		}
		Settings s = config.settings;
		Throttle t = cr.rule.throttle;
		if (s.muteUntil > now) {
			return "muted";
		}
		if (!t.ignoreQuietHours && inQuietHours(s)) {
			return "quiet hours";
		}
		boolean onlyUnfocused = t.onlyWhenUnfocused != null ? t.onlyWhenUnfocused : s.defaultOnlyWhenUnfocused;
		if (onlyUnfocused && ctx.signal("session.window_focused", Map.of()).asBool()) {
			return "window focused";
		}
		if (t.oncePerSession && st.firedThisSession) {
			return "already sent this session";
		}
		if (t.cooldownSeconds > 0 && st.lastFiredAt != RuleState.NEVER && now - st.lastFiredAt < t.cooldownSeconds * 1000) {
			return "cooldown";
		}
		while (!st.recent.isEmpty() && now - st.recent.peekFirst() > 3_600_000L) {
			st.recent.pollFirst();
		}
		if (t.maxPerHour > 0 && st.recent.size() >= t.maxPerHour) {
			return "hourly limit";
		}
		return null;
	}

	private boolean inScope(Scope scope, EvalContext ctx) {
		if (scope == null) {
			return true;
		}
		Value sp = ctx.signal("world.is_singleplayer", Map.of());
		if (!sp.isNone()) {
			if (sp.asBool()) {
				if (!scope.singleplayer) {
					return false;
				}
				if (scope.worlds != null && !scope.worlds.isEmpty()
						&& !Glob.anyMatches(scope.worlds, ctx.signal("world.name", Map.of()).asString())) {
					return false;
				}
			} else {
				if (!scope.multiplayer) {
					return false;
				}
				if (scope.servers != null && !scope.servers.isEmpty()
						&& !Glob.anyMatches(scope.servers, ctx.signal("server.address", Map.of()).asString())) {
					return false;
				}
			}
		}
		if (scope.dimensions != null && !scope.dimensions.isEmpty()) {
			Value dim = ctx.signal("player.dimension", Map.of());
			if (dim.isNone()) {
				return false;
			}
			boolean any = false;
			for (String d : scope.dimensions) {
				if (Ops.equalsLoose(dim, Value.str(d), true, tags)) {
					any = true;
					break;
				}
			}
			return any;
		}
		return true;
	}

	public boolean inQuietHours(Settings s) {
		if (s.quietHours == null || !s.quietHours.enabled) {
			return false;
		}
		try {
			LocalTime start = LocalTime.parse(pad(s.quietHours.start));
			LocalTime end = LocalTime.parse(pad(s.quietHours.end));
			LocalTime t = clock.now().toLocalTime();
			if (start.equals(end)) {
				return false;
			}
			return start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end) : !t.isBefore(start) || t.isBefore(end);
		} catch (DateTimeParseException | NullPointerException e) {
			return false;
		}
	}

	private static String pad(String hhmm) {
		String t = hhmm.trim();
		return t.length() == 4 ? "0" + t : t;
	}

	private void deliver(CompiledRule cr, EvalContext ctx, boolean test) {
		for (CompiledRule.CompiledTarget t : cr.targets) {
			NotifierConfig nc = config.notifier(t.notifierId());
			if (nc == null || (!nc.enabled && !test)) {
				continue;
			}
			List<Template> all = new ArrayList<>(t.templates());
			all.addAll(notifierTemplates.apply(nc.id));
			Snapshot snap = Snapshot.capture(all, ctx);
			Notification n = new Notification(t.title(), t.body(), t.priority(), t.tags(), t.click(), snap,
					cr.rule.id, cr.rule.name, test, clock.millis());
			try {
				sink.deliver(new Delivery(nc.id, n));
			} catch (RuntimeException e) {
				LOG.warn("Sculkwire could not queue a notification for {}", nc.id, e);
			}
		}
	}

	private void outcome(CompiledRule cr, RuleState st, long now, String what) {
		st.lastOutcome = what;
		st.lastOutcomeAt = now;
		if (debug != null) {
			debug.accept("rule " + cr.rule.id + ": " + what);
		}
	}

	private RuleState state(CompiledRule cr) {
		return states.computeIfAbsent(cr.rule.id, k -> new RuleState());
	}

	private EvalContext context(CompiledRule cr, Map<String, Value> payload, RuleState st) {
		Map<String, Value> builtins = new HashMap<>();
		builtins.put("rule.name", Value.str(cr.rule.name));
		builtins.put("rule.id", Value.str(cr.rule.id));
		builtins.put("now", Value.num(clock.millis()));
		return new EvalContext(cache, payload, vars, builtins, tags, st.memory);
	}

	/** A context without a rule (for the inspector and template previews outside a rule). */
	public EvalContext freeContext(Map<String, Value> payload) {
		Map<String, Value> builtins = new HashMap<>();
		builtins.put("now", Value.num(clock.millis()));
		return new EvalContext(cache, payload == null ? new LinkedHashMap<>() : payload, vars, builtins, tags, new HashMap<>());
	}
}
