package dev.sculkwire.core.notify.types;

import com.google.gson.JsonObject;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.notify.HttpNotifier;
import dev.sculkwire.core.notify.HttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.Notifier;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.notify.Priority;
import dev.sculkwire.core.notify.SecretSource;
import dev.sculkwire.core.notify.SendResult;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.Json;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** A self-hosted Gotify server. */
public final class GotifyType implements NotifierType {
	public static final String ID = "gotify";

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("server", FieldKind.URL, "Server").asRequired().withHint("e.g. https://gotify.example.com"),
			FieldSpec.of("app_token", FieldKind.SECRET, "App token").asRequired().withHint("Apps → create an application → token"),
			FieldSpec.of("markdown", FieldKind.BOOL, "Markdown").withDefault(false),
			FieldSpec.of("allow_insecure", FieldKind.BOOL, "Allow insecure HTTP").withDefault(false));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Gotify";
	}

	@Override
	public String description() {
		return "Push to your own Gotify server and its Android app.";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 500;
	}

	static int priority(Priority p) {
		return switch (p) {
			case MIN -> 0;
			case LOW -> 2;
			case DEFAULT -> 5;
			case HIGH -> 7;
			case URGENT -> 10;
		};
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		String server = field("server").string(cfg.settings).trim().replaceAll("/+$", "");
		String token = secrets.get(cfg.id, "app_token").trim();
		boolean markdown = field("markdown").bool(cfg.settings);
		return new HttpNotifier(http) {
			@Override
			public CompletableFuture<SendResult> send(Notification n) {
				JsonObject o = new JsonObject();
				String title = n.title(OutputContext.PLAIN).strip();
				o.addProperty("title", (n.test() ? "[test] " : "") + (title.isEmpty() ? "Sculkwire" : title));
				String body = n.body(OutputContext.PLAIN).strip();
				o.addProperty("message", body.isEmpty() ? title : body);
				o.addProperty("priority", priority(n.priority()));
				JsonObject extras = new JsonObject();
				if (markdown) {
					JsonObject display = new JsonObject();
					display.addProperty("contentType", "text/markdown");
					extras.add("client::display", display);
				}
				String click = n.click(OutputContext.PLAIN).strip();
				if (!click.isEmpty()) {
					JsonObject notification = new JsonObject();
					JsonObject c = new JsonObject();
					c.addProperty("url", click);
					notification.add("click", c);
					extras.add("client::notification", notification);
				}
				if (extras.size() > 0) {
					o.add("extras", extras);
				}
				return call("POST", server + "/message", Map.of("Content-Type", "application/json", "X-Gotify-Key", token),
						Json.COMPACT.toJson(o), Duration.ofSeconds(15));
			}
		};
	}
}
