package dev.sculkwire.core.notify.types;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.Json;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** ntfy.sh or a self-hosted ntfy server, using JSON publishing (UTF-8 safe). */
public final class NtfyType implements NotifierType {
	public static final String ID = "ntfy";
	static final int MAX_MESSAGE_BYTES = 4096;

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("server", FieldKind.URL, "Server").withDefault("https://ntfy.sh").asRequired()
					.withHint("https://ntfy.sh or your own ntfy server"),
			FieldSpec.of("topic", FieldKind.TEXT, "Topic").asRequired()
					.withHint("On the public ntfy.sh anyone who knows the topic can read it: use a long random name or a token."),
			FieldSpec.of("auth", FieldKind.ENUM, "Authentication").withOptions("none", "token", "basic").withDefault("none"),
			FieldSpec.of("token", FieldKind.SECRET, "Access token").withHint("tk_... (when Authentication is token)"),
			FieldSpec.of("username", FieldKind.TEXT, "Username").withHint("When Authentication is basic"),
			FieldSpec.of("password", FieldKind.SECRET, "Password"),
			FieldSpec.of("default_tags", FieldKind.STRING_LIST, "Extra tags").withHint("Emoji short codes, e.g. warning, skull"),
			FieldSpec.of("markdown", FieldKind.BOOL, "Markdown").withDefault(false),
			FieldSpec.of("icon_url", FieldKind.URL, "Icon URL"),
			FieldSpec.of("allow_insecure", FieldKind.BOOL, "Allow insecure HTTP").withDefault(false));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "ntfy";
	}

	@Override
	public String description() {
		return "Push notifications to your phone through ntfy.sh or your own ntfy server.";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 2000;
	}

	@Override
	public List<String> validate(NotifierConfig cfg, SecretSource secrets) {
		List<String> out = new ArrayList<>(NotifierType.super.validate(cfg, secrets));
		String auth = field("auth").string(cfg.settings);
		if (auth.equals("token") && secrets.get(cfg.id, "token").isBlank()) {
			out.add("Access token is required for token authentication");
		}
		if (auth.equals("basic") && (field("username").string(cfg.settings).isBlank() || secrets.get(cfg.id, "password").isBlank())) {
			out.add("Username and password are required for basic authentication");
		}
		String topic = field("topic").string(cfg.settings);
		if (!topic.isBlank() && !topic.matches("[A-Za-z0-9_-]{1,64}")) {
			out.add("Topic may only contain letters, digits, - and _ (max 64)");
		}
		return out;
	}

	/** True when the topic is on the public server without authentication (anyone could read it). */
	public static boolean isPublicUnauthenticated(NotifierConfig cfg) {
		String server = Json.str(cfg.settings, "server", "https://ntfy.sh").trim();
		String auth = Json.str(cfg.settings, "auth", "none");
		return server.replaceAll("/+$", "").equalsIgnoreCase("https://ntfy.sh") && auth.equals("none");
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		String server = field("server").string(cfg.settings).trim().replaceAll("/+$", "");
		String topic = field("topic").string(cfg.settings).trim();
		String auth = field("auth").string(cfg.settings);
		String token = secrets.get(cfg.id, "token");
		String user = field("username").string(cfg.settings);
		String pass = secrets.get(cfg.id, "password");
		List<String> extraTags = field("default_tags").list(cfg.settings);
		boolean markdown = field("markdown").bool(cfg.settings);
		String icon = field("icon_url").string(cfg.settings).trim();

		return new HttpNotifier(http) {
			@Override
			public CompletableFuture<SendResult> send(Notification n) {
				String title = n.title(OutputContext.PLAIN).strip();
				String body = n.body(OutputContext.PLAIN).strip();
				if (body.isEmpty()) {
					body = title.isEmpty() ? "Sculkwire" : title;
					title = "";
				}
				JsonObject o = new JsonObject();
				o.addProperty("topic", topic);
				o.addProperty("message", truncateBytes(body, MAX_MESSAGE_BYTES));
				if (!title.isEmpty()) {
					o.addProperty("title", truncate(title, 250));
				}
				o.addProperty("priority", n.priority().ntfy());
				Set<String> tags = new LinkedHashSet<>(n.tags());
				tags.addAll(extraTags);
				if (n.test()) {
					tags.add("test_tube");
				}
				if (!tags.isEmpty()) {
					JsonArray a = new JsonArray();
					tags.forEach(a::add);
					o.add("tags", a);
				}
				String click = n.click(OutputContext.PLAIN).strip();
				if (!click.isEmpty()) {
					o.addProperty("click", click);
				}
				if (markdown) {
					o.addProperty("markdown", true);
				}
				if (!icon.isEmpty()) {
					o.addProperty("icon", icon);
				}
				Map<String, String> headers = new LinkedHashMap<>();
				headers.put("Content-Type", "application/json");
				if (auth.equals("token") && !token.isBlank()) {
					headers.put("Authorization", "Bearer " + token.trim());
				} else if (auth.equals("basic") && !user.isBlank()) {
					String cred = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
					headers.put("Authorization", "Basic " + cred);
				}
				return call("POST", server + "/", headers, Json.COMPACT.toJson(o), Duration.ofSeconds(15));
			}
		};
	}
}
