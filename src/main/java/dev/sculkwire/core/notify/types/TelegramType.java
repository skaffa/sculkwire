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

/** Telegram bot message (sendMessage). Low priorities are delivered silently. */
public final class TelegramType implements NotifierType {
	public static final String ID = "telegram";

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("bot_token", FieldKind.SECRET, "Bot token").asRequired().withHint("From @BotFather, like 123456:ABC-..."),
			FieldSpec.of("chat_id", FieldKind.TEXT, "Chat id").asRequired()
					.withHint("Your user or group id (message @userinfobot to find yours)"),
			FieldSpec.of("format", FieldKind.ENUM, "Formatting").withOptions("html", "plain").withDefault("html"),
			FieldSpec.of("silent", FieldKind.BOOL, "Always silent").withDefault(false),
			FieldSpec.of("api_base", FieldKind.URL, "API base URL").withDefault("https://api.telegram.org")
					.withHint("Only change this for a self-hosted Bot API server"));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Telegram";
	}

	@Override
	public String description() {
		return "Sends a message from your Telegram bot to you or a group.";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 1000;
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		String token = secrets.get(cfg.id, "bot_token").trim();
		String chat = field("chat_id").string(cfg.settings).trim();
		boolean html = field("format").string(cfg.settings).equals("html");
		boolean silent = field("silent").bool(cfg.settings);
		String base = field("api_base").string(cfg.settings).trim().replaceAll("/+$", "");
		return new HttpNotifier(http) {
			@Override
			public CompletableFuture<SendResult> send(Notification n) {
				OutputContext ctx = html ? OutputContext.TELEGRAM_HTML : OutputContext.PLAIN;
				String title = html ? n.render(n.title(), ctx) : n.title(OutputContext.PLAIN);
				String body = n.body(ctx).strip();
				StringBuilder text = new StringBuilder();
				if (n.test()) {
					text.append("[test] ");
				}
				if (!title.isBlank()) {
					text.append(html ? "<b>" + title.strip() + "</b>" : title.strip());
					if (!body.isEmpty()) {
						text.append('\n');
					}
				}
				text.append(body);
				JsonObject o = new JsonObject();
				o.addProperty("chat_id", chat);
				o.addProperty("text", truncate(text.toString(), 4096));
				if (html) {
					o.addProperty("parse_mode", "HTML");
				}
				o.addProperty("disable_notification", silent || n.priority().ordinal() <= Priority.LOW.ordinal());
				String click = n.click(OutputContext.PLAIN).strip();
				if (click.startsWith("https://") || click.startsWith("http://")) {
					String link = html ? click.replace("&", "&amp;").replace("<", "&lt;") : click;
					o.addProperty("text", truncate(text + "\n" + link, 4096));
				}
				return call("POST", base + "/bot" + token + "/sendMessage", Map.of("Content-Type", "application/json"),
						Json.COMPACT.toJson(o), Duration.ofSeconds(15));
			}
		};
	}
}
