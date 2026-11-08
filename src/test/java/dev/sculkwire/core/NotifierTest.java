package dev.sculkwire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import dev.sculkwire.core.config.Secrets;
import dev.sculkwire.core.dispatch.Dispatcher;
import dev.sculkwire.core.dispatch.History;
import dev.sculkwire.core.engine.RuleEngine;
import dev.sculkwire.core.model.Condition;
import dev.sculkwire.core.model.MessageTemplate;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Operand;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Target;
import dev.sculkwire.core.model.Trigger;
import dev.sculkwire.core.notify.HttpTransport;
import dev.sculkwire.core.notify.JdkHttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.NotifierRegistry;
import dev.sculkwire.core.notify.Priority;
import dev.sculkwire.core.notify.UrlPolicy;
import dev.sculkwire.core.template.Snapshot;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.util.Clock;
import dev.sculkwire.core.value.Value;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifierTest {
	record Req(String method, String path, String query, Map<String, List<String>> headers, String body) {
		String header(String name) {
			for (Map.Entry<String, List<String>> e : headers.entrySet()) {
				if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
					return e.getValue().get(0);
				}
			}
			return null;
		}

		JsonObject json() {
			return JsonParser.parseString(body).getAsJsonObject();
		}
	}

	@TempDir
	Path dir;

	private HttpServer server;
	private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
	private final List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
	private String base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(),
					ex.getRequestHeaders(), body));
			int status = statuses.isEmpty() ? 200 : statuses.remove(0);
			byte[] out = (status == 429 ? "{\"retry_after\": 0.05}" : "{}").getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private static Notification note(String title, String body, Priority p) {
		return new Notification(Template.literal(title), Template.literal(body), p, List.of("warning"), Template.EMPTY,
				Snapshot.of(Map.of()), "rule-1", "Low HP", false, 1_700_000_000_000L);
	}

	private final class Setup {
		final SculkwireConfig cfg = new SculkwireConfig();
		final Secrets secrets = Secrets.inMemory();
		final History history = new History(dir.resolve("history.jsonl"), 50);
		Dispatcher dispatcher;

		Setup(HttpTransport http) {
			dispatcher = new Dispatcher(NotifierRegistry.withBuiltins(), () -> cfg, secrets, secrets::allValues, http, history,
					Clock.system());
			dispatcher.setTiming(new long[] {20, 20, 20}, 0);
		}

		NotifierConfig add(String id, String type) {
			NotifierConfig n = NotifierConfig.of(id, id, type);
			cfg.notifiers.add(n);
			return n;
		}

		History.Entry send(String id, Notification n) throws Exception {
			return dispatcher.submit(id, n).get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void ntfyUsesJsonPublishWithAuth() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		NotifierConfig n = s.add("phone", "ntfy");
		n.settings.addProperty("server", base);
		n.settings.addProperty("topic", "sculk_test");
		n.settings.addProperty("auth", "token");
		s.secrets.set("phone", "token", "tk_abcdefghijkl");
		History.Entry e = s.send("phone", note("Low \"HP\"", "2 ❤ left", Priority.HIGH));
		assertEquals(History.Status.SENT, e.status(), e.error());
		Req r = requests.get(0);
		assertEquals("POST", r.method());
		assertEquals("/", r.path());
		assertEquals("Bearer tk_abcdefghijkl", r.header("Authorization"));
		JsonObject o = r.json();
		assertEquals("sculk_test", o.get("topic").getAsString());
		assertEquals("Low \"HP\"", o.get("title").getAsString());
		assertEquals("2 ❤ left", o.get("message").getAsString());
		assertEquals(4, o.get("priority").getAsInt());
		assertEquals("warning", o.getAsJsonArray("tags").get(0).getAsString());
	}

	@Test
	void discordLocksDownMentions() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		NotifierConfig n = s.add("dc", "discord");
		n.settings.addProperty("mention", "<@42>");
		s.secrets.set("dc", "url", base + "/api/webhooks/1/token");
		Notification chat = new Notification(Template.literal("Hi"), Template.parse("> {msg.text}"), Priority.URGENT, List.of(),
				Template.EMPTY, Snapshot.of(Map.of("msg.text", Value.str("@everyone look **here**"))), "r", "Rule", false, 0);
		History.Entry e = s.send("dc", chat);
		assertEquals(History.Status.SENT, e.status(), e.error());
		Req r = requests.get(0);
		assertEquals("/api/webhooks/1/token", r.path());
		assertEquals("wait=true", r.query());
		JsonObject o = r.json();
		assertEquals(0, o.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
		assertEquals("42", o.getAsJsonObject("allowed_mentions").getAsJsonArray("users").get(0).getAsString());
		assertEquals("<@42>", o.get("content").getAsString());
		JsonObject embed = o.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertFalse(embed.get("description").getAsString().contains("@everyone"));
		assertTrue(embed.get("description").getAsString().contains("\\*\\*here\\*\\*"));
		assertTrue(embed.get("description").getAsString().startsWith("> "), "template's own markdown is kept");
		assertEquals(0xE74C3C, embed.get("color").getAsInt());
	}

	@Test
	void webhookRendersTemplatesWithEscaping() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		s.add("hook", "webhook");
		s.secrets.set("hook", "url", base + "/hook/{player.name}");
		s.secrets.set("hook", "headers", "{\"X-Token\": \"abc\"}");
		Notification n = new Notification(Template.literal("Say \"hi\""), Template.literal("line1\nline2"), Priority.DEFAULT,
				List.of(), Template.EMPTY, Snapshot.of(Map.of("player.name", Value.str("Steve Jobs"))), "r", "Rule", false, 0);
		History.Entry e = s.send("hook", n);
		assertEquals(History.Status.SENT, e.status(), e.error());
		Req r = requests.get(0);
		assertEquals("/hook/Steve Jobs", r.path());
		assertEquals("abc", r.header("X-Token"));
		JsonObject o = r.json(); // valid JSON despite quotes and newlines
		assertEquals("Say \"hi\"", o.get("title").getAsString());
		assertEquals("line1\nline2", o.get("message").getAsString());
		assertEquals("Steve Jobs", o.get("player").getAsString());
	}

	@Test
	void retriesServerErrorsAndHonorsRateLimits() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		s.add("hook", "webhook");
		s.secrets.set("hook", "url", base + "/x");
		statuses.addAll(List.of(500, 429, 200));
		History.Entry e = s.send("hook", note("t", "b", Priority.DEFAULT));
		assertEquals(History.Status.SENT, e.status());
		assertEquals(3, e.attempts());
		assertEquals(3, requests.size());
	}

	@Test
	void clientErrorsFailWithoutRetry() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		s.add("hook", "webhook");
		s.secrets.set("hook", "url", base + "/x");
		statuses.add(404);
		History.Entry e = s.send("hook", note("t", "b", Priority.DEFAULT));
		assertEquals(History.Status.FAILED, e.status());
		assertEquals(1, e.attempts());
		assertEquals(404, e.httpStatus());
		assertEquals(1, s.dispatcher.stats("hook").failed);
	}

	@Test
	void giveUpAfterRetriesAndWarnOnRepeatedFailure() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		HttpTransport down = call -> {
			calls.incrementAndGet();
			return CompletableFuture.failedFuture(new java.net.ConnectException("refused"));
		};
		Setup s = new Setup(down);
		s.add("hook", "webhook");
		s.secrets.set("hook", "url", "https://example.invalid/x");
		List<String> warnings = Collections.synchronizedList(new ArrayList<>());
		s.dispatcher.setFailureWarning(warnings::add);
		for (int i = 0; i < 3; i++) {
			History.Entry e = s.send("hook", note("t", "b", Priority.DEFAULT));
			assertEquals(History.Status.FAILED, e.status());
			assertEquals(4, e.attempts());
		}
		assertEquals(12, calls.get());
		assertEquals(1, warnings.size());
	}

	@Test
	void queueOverflowDropsOldest() throws Exception {
		CompletableFuture<HttpTransport.HttpResult> never = new CompletableFuture<>();
		Setup s = new Setup(call -> never);
		s.add("hook", "webhook");
		s.secrets.set("hook", "url", "https://example.invalid/x");
		s.dispatcher.submit("hook", note("first", "", Priority.DEFAULT));
		Thread.sleep(100); // first is in flight
		List<CompletableFuture<History.Entry>> all = new ArrayList<>();
		for (int i = 0; i < Dispatcher.MAX_QUEUE + 1; i++) {
			all.add(s.dispatcher.submit("hook", note("n" + i, "", Priority.DEFAULT)));
		}
		History.Entry dropped = all.get(0).get(5, TimeUnit.SECONDS);
		assertEquals(History.Status.DROPPED, dropped.status());
		assertEquals("n0", dropped.title());
	}

	@Test
	void disabledNotifiersOnlyTakeTests() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		NotifierConfig n = s.add("hook", "webhook");
		n.enabled = false;
		s.secrets.set("hook", "url", base + "/x");
		assertEquals(History.Status.FAILED, s.send("hook", note("t", "b", Priority.DEFAULT)).status());
		assertEquals(History.Status.SENT, s.send("hook", Notification.simple("Test", "from Sculkwire", Priority.DEFAULT, true)).status());
	}

	@Test
	void validation() {
		NotifierRegistry reg = NotifierRegistry.withBuiltins();
		Secrets secrets = Secrets.inMemory();
		NotifierConfig ntfy = NotifierConfig.of("a", "a", "ntfy");
		assertTrue(reg.get("ntfy").validate(ntfy, secrets).stream().anyMatch(s -> s.contains("Topic")));
		ntfy.settings.addProperty("topic", "has spaces!");
		assertTrue(reg.get("ntfy").validate(ntfy, secrets).stream().anyMatch(s -> s.contains("letters")));
		ntfy.settings.addProperty("topic", "ok_topic");
		ntfy.settings.addProperty("server", "http://ntfy.example.com");
		assertTrue(reg.get("ntfy").validate(ntfy, secrets).stream().anyMatch(s -> s.contains("https")));
		ntfy.settings.addProperty("server", "http://192.168.1.5:8080");
		assertTrue(reg.get("ntfy").validate(ntfy, secrets).isEmpty());

		NotifierConfig dc = NotifierConfig.of("d", "d", "discord");
		secrets.set("d", "url", "https://example.com/hook");
		assertTrue(reg.get("discord").validate(dc, secrets).stream().anyMatch(s -> s.contains("Discord webhook")));

		assertNull(UrlPolicy.check("https://x.y/{player.name}", false));
		assertNotNull(UrlPolicy.check("ftp://x.y", false));
	}

	@Test
	void historyPersistsAndRedacts() throws Exception {
		Setup s = new Setup(new JdkHttpTransport());
		s.add("dc", "discord");
		s.secrets.set("dc", "url", base + "/api/webhooks/1/sekrettoken");
		statuses.add(400);
		History.Entry e = s.send("dc", note("t", "b", Priority.DEFAULT));
		assertEquals(History.Status.FAILED, e.status());
		History reloaded = new History(dir.resolve("history.jsonl"), 50);
		reloaded.load();
		assertEquals(1, reloaded.entries().size());
		assertEquals("Low HP", reloaded.entries().get(0).ruleName());
		assertFalse(reloaded.entries().get(0).error().contains("sekrettoken"));
	}

	@Test
	void engineCapturesNotifierTemplateValues() throws Exception {
		TestWorld w = new TestWorld();
		Setup s = new Setup(new JdkHttpTransport());
		s.cfg.notifiers.clear();
		NotifierConfig hook = s.add("hook", "webhook");
		hook.settings.addProperty("body", "{\"hp\": \"{player.health}\", \"t\": \"{notification.title}\"}");
		s.secrets.set("hook", "url", base + "/x");
		RuleEngine engine = new RuleEngine(w.signals, w.events, w.clock, d -> s.dispatcher.submit(d.notifierId(), d.notification()));
		engine.setNotifierTemplates(s.dispatcher::templatesFor);
		Rule r = new Rule();
		r.id = "hp";
		r.name = "hp";
		r.trigger = Trigger.condition(Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6)));
		r.trigger.checkEveryTicks = 1;
		r.message = MessageTemplate.of("Low {player.health}", "");
		r.targets.add(Target.of("hook"));
		s.cfg.rules.add(r);
		w.set("player.health", 20);
		engine.load(s.cfg);
		engine.onJoin();
		for (int i = 0; i < 60; i++) {
			engine.tick();
			w.clock.t += 50;
		}
		w.set("player.health", 3);
		engine.tick();
		w.set("player.health", 19); // value changes after firing; the snapshot keeps 3
		assertTrue(s.dispatcher.flush(5000));
		JsonObject o = requests.get(0).json();
		assertEquals("3", o.get("hp").getAsString());
		assertEquals("Low 3", o.get("t").getAsString());
	}
}
