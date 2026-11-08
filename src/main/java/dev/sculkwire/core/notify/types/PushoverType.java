package dev.sculkwire.core.notify.types;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/** Pushover (pushover.net). Urgent maps to Pushover priority 1; emergency priority is not used. */
public final class PushoverType implements NotifierType {
	public static final String ID = "pushover";

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("app_token", FieldKind.SECRET, "Application token").asRequired(),
			FieldSpec.of("user_key", FieldKind.SECRET, "User key").asRequired(),
			FieldSpec.of("device", FieldKind.TEXT, "Device").withHint("Empty = all your devices"),
			FieldSpec.of("sound", FieldKind.TEXT, "Sound").withHint("A Pushover sound name; empty = default"),
			FieldSpec.of("api_base", FieldKind.URL, "API base URL").withDefault("https://api.pushover.net")
					.withHint("Leave as is"));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Pushover";
	}

	@Override
	public String description() {
		return "Push notifications through Pushover.";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 1000;
	}

	static int priority(Priority p) {
		return switch (p) {
			case MIN -> -2;
			case LOW -> -1;
			case DEFAULT -> 0;
			case HIGH, URGENT -> 1;
		};
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		String token = secrets.get(cfg.id, "app_token").trim();
		String user = secrets.get(cfg.id, "user_key").trim();
		String device = field("device").string(cfg.settings).trim();
		String sound = field("sound").string(cfg.settings).trim();
		String base = field("api_base").string(cfg.settings).trim().replaceAll("/+$", "");
		return new HttpNotifier(http) {
			@Override
			public CompletableFuture<SendResult> send(Notification n) {
				Map<String, String> form = new LinkedHashMap<>();
				form.put("token", token);
				form.put("user", user);
				String title = n.title(OutputContext.PLAIN).strip();
				String body = n.body(OutputContext.PLAIN).strip();
				form.put("title", truncate((n.test() ? "[test] " : "") + (title.isEmpty() ? "Sculkwire" : title), 250));
				form.put("message", truncate(body.isEmpty() ? title : body, 1024));
				form.put("priority", String.valueOf(priority(n.priority())));
				String click = n.click(OutputContext.PLAIN).strip();
				if (!click.isEmpty()) {
					form.put("url", click);
				}
				if (!device.isEmpty()) {
					form.put("device", device);
				}
				if (!sound.isEmpty()) {
					form.put("sound", sound);
				}
				StringJoiner sj = new StringJoiner("&");
				form.forEach((k, v) -> sj.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
				return call("POST", base + "/1/messages.json", Map.of("Content-Type", "application/x-www-form-urlencoded"),
						sj.toString(), Duration.ofSeconds(15));
			}
		};
	}
}
