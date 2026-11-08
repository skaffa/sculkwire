package dev.sculkwire.core.notify.types;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.notify.HttpNotifier;
import dev.sculkwire.core.notify.HttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.Notifier;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.notify.SecretSource;
import dev.sculkwire.core.notify.SendResult;
import dev.sculkwire.core.notify.UrlPolicy;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.template.VariableResolver;
import dev.sculkwire.core.util.Json;
import dev.sculkwire.core.value.Value;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Any HTTP endpoint. URL, headers and body are templates; besides the usual variables they can use
 * {@code {notification.title}}, {@code {notification.body}}, {@code {notification.priority}},
 * {@code {notification.tags}}, {@code {notification.click}} and {@code {rule.name}}. Substituted values
 * are escaped for where they go (JSON string, URL, header, form).
 */
public final class WebhookType implements NotifierType {
	public static final String ID = "webhook";

	public static final String DEFAULT_BODY = """
			{
			  "title": "{notification.title}",
			  "message": "{notification.body}",
			  "priority": "{notification.priority}",
			  "rule": "{rule.name}",
			  "player": "{player.name}"
			}""";

	/** Body templates offered by the editor. */
	public static final Map<String, String[]> PRESETS = presets();

	private static Map<String, String[]> presets() {
		Map<String, String[]> m = new LinkedHashMap<>();
		m.put("JSON (all fields)", new String[] {"application/json", DEFAULT_BODY});
		m.put("Slack incoming webhook", new String[] {"application/json", "{\"text\": \"*{notification.title}*\\n{notification.body}\"}"});
		m.put("Home Assistant webhook", new String[] {"application/json",
				"{\"title\": \"{notification.title}\", \"message\": \"{notification.body}\", \"priority\": \"{notification.priority}\"}"});
		m.put("Mattermost / Rocket.Chat", new String[] {"application/json", "{\"text\": \"**{notification.title}**\\n{notification.body}\"}"});
		m.put("Plain text", new String[] {"text/plain", "{notification.title}\n{notification.body}"});
		m.put("Form fields", new String[] {"application/x-www-form-urlencoded", "title={notification.title}&message={notification.body}"});
		return m;
	}

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("url", FieldKind.SECRET, "URL").asRequired()
					.withHint("https://... ; may contain {variables}. Stored as a secret since URLs often hold tokens."),
			FieldSpec.of("method", FieldKind.ENUM, "Method").withOptions("POST", "PUT", "PATCH", "GET").withDefault("POST"),
			FieldSpec.of("content_type", FieldKind.ENUM, "Content type")
					.withOptions("application/json", "text/plain", "application/x-www-form-urlencoded").withDefault("application/json"),
			FieldSpec.of("body", FieldKind.TEMPLATE, "Body").withDefault(DEFAULT_BODY)
					.withHint("Use {notification.title}, {notification.body}, {notification.priority}, {rule.name} and any variable."),
			FieldSpec.of("headers", FieldKind.KEY_VALUE_LIST, "Headers").asSecret()
					.withHint("e.g. Authorization: Bearer ... (stored as a secret)"),
			FieldSpec.of("success_codes", FieldKind.TEXT, "Success codes").withDefault("2xx").withHint("2xx, or a list like 200,204"),
			FieldSpec.of("timeout_seconds", FieldKind.INT, "Timeout (s)").withDefault(15),
			FieldSpec.of("allow_insecure", FieldKind.BOOL, "Allow insecure HTTP").withDefault(false));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Webhook";
	}

	@Override
	public String description() {
		return "Calls any HTTP endpoint with a body you design (Slack, Home Assistant, n8n, your own server).";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 500;
	}

	@Override
	public List<String> validate(NotifierConfig cfg, SecretSource secrets) {
		List<String> out = new ArrayList<>(NotifierType.super.validate(cfg, secrets));
		String url = secrets.get(cfg.id, "url");
		if (!url.isBlank()) {
			String err = UrlPolicy.check(url, field("allow_insecure").bool(cfg.settings));
			if (err != null) {
				out.add("URL " + err);
			}
		}
		String headers = secrets.get(cfg.id, "headers");
		if (!headers.isBlank() && parseHeaders(headers) == null) {
			out.add("Headers are not valid");
		}
		return out;
	}

	static Map<String, String> parseHeaders(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		if (json == null || json.isBlank()) {
			return out;
		}
		try {
			JsonElement e = JsonParser.parseString(json);
			if (!e.isJsonObject()) {
				return null;
			}
			for (Map.Entry<String, JsonElement> h : e.getAsJsonObject().entrySet()) {
				out.put(h.getKey().trim(), h.getValue().isJsonPrimitive() ? h.getValue().getAsString() : h.getValue().toString());
			}
			return out;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	static boolean isSuccess(String spec, int status) {
		String s = spec == null || spec.isBlank() ? "2xx" : spec;
		for (String part : s.split(",")) {
			String p = part.trim().toLowerCase(Locale.ROOT);
			if (p.length() == 3 && p.endsWith("xx") && Character.isDigit(p.charAt(0))) {
				if (status / 100 == p.charAt(0) - '0') {
					return true;
				}
			} else {
				try {
					if (Integer.parseInt(p) == status) {
						return true;
					}
				} catch (NumberFormatException ignored) {
					// skip
				}
			}
		}
		return false;
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		Template url = Template.parse(secrets.get(cfg.id, "url").trim());
		String method = field("method").string(cfg.settings).toUpperCase(Locale.ROOT);
		String contentType = field("content_type").string(cfg.settings);
		Template body = Template.parse(field("body").string(cfg.settings));
		Map<String, String> rawHeaders = parseHeaders(secrets.get(cfg.id, "headers"));
		Map<String, Template> headers = new LinkedHashMap<>();
		if (rawHeaders != null) {
			rawHeaders.forEach((k, v) -> headers.put(k, Template.parse(v)));
		}
		String success = field("success_codes").string(cfg.settings);
		int timeout = (int) Math.max(1, Math.min(60, field("timeout_seconds").number(cfg.settings, 15)));
		OutputContext bodyCtx = switch (contentType) {
			case "application/json" -> OutputContext.JSON_STRING;
			case "application/x-www-form-urlencoded" -> OutputContext.FORM_COMPONENT;
			default -> OutputContext.PLAIN;
		};
		List<Template> templates = new ArrayList<>();
		templates.add(url);
		templates.add(body);
		templates.addAll(headers.values());

		return new HttpNotifier(http) {
			@Override
			public List<Template> templates() {
				return templates;
			}

			@Override
			public CompletableFuture<SendResult> send(Notification n) {
				VariableResolver vars = (name, params) -> switch (name) {
					case "notification.title" -> Value.str(n.title(OutputContext.PLAIN));
					case "notification.body" -> Value.str(n.body(OutputContext.PLAIN));
					case "notification.priority" -> Value.str(n.priority().id());
					case "notification.tags" -> Value.list(n.tags());
					case "notification.click" -> Value.str(n.click(OutputContext.PLAIN));
					case "notification.test" -> Value.bool(n.test());
					case "rule.name" -> Value.str(n.ruleName());
					case "rule.id" -> Value.str(n.ruleId());
					default -> n.vars().resolve(name, params);
				};
				Map<String, String> h = new LinkedHashMap<>();
				if (!method.equals("GET")) {
					h.put("Content-Type", contentType + (contentType.startsWith("text/") ? "; charset=utf-8" : ""));
				}
				headers.forEach((k, v) -> h.put(k, v.render(vars, OutputContext.HEADER)));
				String b = method.equals("GET") ? null : body.render(vars, bodyCtx);
				return call(method, url.render(vars, OutputContext.URL_COMPONENT), h, b, Duration.ofSeconds(timeout))
						.thenApply(r -> {
							if (r.ok() && !isSuccess(success, r.status())) {
								return SendResult.fail(r.status(), "HTTP " + r.status() + " is not in the success codes");
							}
							if (!r.ok() && r.status() > 0 && isSuccess(success, r.status())) {
								return SendResult.ok(r.status());
							}
							return r;
						});
			}
		};
	}

	/** Sample body check: true when the default body renders to valid JSON. */
	public static boolean bodyIsValidJson(String rendered) {
		try {
			JsonParser.parseString(rendered);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	static String compact(JsonObject o) {
		return Json.COMPACT.toJson(o);
	}
}
