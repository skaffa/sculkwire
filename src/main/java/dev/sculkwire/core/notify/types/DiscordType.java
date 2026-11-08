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
import dev.sculkwire.core.notify.Priority;
import dev.sculkwire.core.notify.SecretSource;
import dev.sculkwire.core.notify.SendResult;
import dev.sculkwire.core.notify.UrlPolicy;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discord webhook. Mentions in the message text never ping: only ids in the Mention field are allowed. */
public final class DiscordType implements NotifierType {
	public static final String ID = "discord";
	private static final Pattern USER = Pattern.compile("<@!?(\\d+)>");
	private static final Pattern ROLE = Pattern.compile("<@&(\\d+)>");

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("url", FieldKind.SECRET, "Webhook URL").asRequired()
					.withHint("Channel settings → Integrations → Webhooks → Copy Webhook URL"),
			FieldSpec.of("style", FieldKind.ENUM, "Style").withOptions("embed", "text").withDefault("embed"),
			FieldSpec.of("username", FieldKind.TEXT, "Bot name").withDefault("Sculkwire"),
			FieldSpec.of("avatar_url", FieldKind.URL, "Avatar URL"),
			FieldSpec.of("mention", FieldKind.TEXT, "Mention")
					.withHint("Who to ping, e.g. <@123456789> or <@&roleid>. Mentions inside messages never ping."),
			FieldSpec.of("color", FieldKind.COLOR, "Embed color").withHint("#RRGGBB; empty = by priority"));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Discord webhook";
	}

	@Override
	public String description() {
		return "Posts to a Discord channel through a webhook.";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 400;
	}

	@Override
	public List<String> validate(NotifierConfig cfg, SecretSource secrets) {
		List<String> out = new ArrayList<>(NotifierType.super.validate(cfg, secrets));
		String url = secrets.get(cfg.id, "url");
		if (!url.isBlank()) {
			String err = UrlPolicy.check(url, false);
			if (err != null) {
				out.add("Webhook URL " + err);
			} else if (!url.contains("/api/webhooks/")) {
				out.add("Webhook URL does not look like a Discord webhook (…/api/webhooks/…)");
			}
		}
		return out;
	}

	static int colorFor(Priority p) {
		return switch (p) {
			case MIN -> 0x95A5A6;
			case LOW -> 0x3498DB;
			case DEFAULT -> 0x1FB6C4;
			case HIGH -> 0xE67E22;
			case URGENT -> 0xE74C3C;
		};
	}

	static JsonObject allowedMentions(String mention) {
		JsonObject am = new JsonObject();
		am.add("parse", new JsonArray());
		JsonArray users = new JsonArray();
		JsonArray roles = new JsonArray();
		Matcher u = USER.matcher(mention);
		while (u.find()) {
			users.add(u.group(1));
		}
		Matcher r = ROLE.matcher(mention);
		while (r.find()) {
			roles.add(r.group(1));
		}
		am.add("users", users);
		am.add("roles", roles);
		return am;
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		String url = secrets.get(cfg.id, "url").trim();
		String style = field("style").string(cfg.settings);
		String username = field("username").string(cfg.settings).trim();
		String avatar = field("avatar_url").string(cfg.settings).trim();
		String mention = field("mention").string(cfg.settings).trim();
		String colorText = field("color").string(cfg.settings).trim().replace("#", "");
		Integer fixedColor = null;
		if (!colorText.isEmpty()) {
			try {
				fixedColor = Integer.parseInt(colorText, 16);
			} catch (NumberFormatException ignored) {
				// fall back to the priority color
			}
		}
		Integer color = fixedColor;

		return new HttpNotifier(http) {
			@Override
			public CompletableFuture<SendResult> send(Notification n) {
				JsonObject o = new JsonObject();
				if (!username.isEmpty()) {
					o.addProperty("username", truncate(username, 80));
				}
				if (!avatar.isEmpty()) {
					o.addProperty("avatar_url", avatar);
				}
				o.add("allowed_mentions", allowedMentions(mention));
				String title = n.title(OutputContext.PLAIN).strip();
				String prefix = n.test() ? "[test] " : "";
				if (style.equals("text")) {
					String body = n.body(OutputContext.DISCORD_MARKDOWN).strip();
					StringBuilder sb = new StringBuilder();
					if (!mention.isEmpty()) {
						sb.append(mention).append(' ');
					}
					if (!title.isEmpty()) {
						sb.append("**").append(n.title(OutputContext.DISCORD_MARKDOWN).strip()).append("**");
						if (!body.isEmpty()) {
							sb.append('\n');
						}
					}
					sb.append(body);
					o.addProperty("content", truncate(prefix + sb, 2000));
				} else {
					if (!mention.isEmpty()) {
						o.addProperty("content", mention);
					}
					JsonObject embed = new JsonObject();
					if (!title.isEmpty() || n.test()) {
						embed.addProperty("title", truncate(prefix + title, 256));
					}
					String body = n.body(OutputContext.DISCORD_MARKDOWN).strip();
					if (!body.isEmpty()) {
						embed.addProperty("description", truncate(body, 4000));
					}
					String click = n.click(OutputContext.PLAIN).strip();
					if (click.startsWith("https://") || click.startsWith("http://")) {
						embed.addProperty("url", click);
					}
					embed.addProperty("color", color != null ? color : colorFor(n.priority()));
					embed.addProperty("timestamp", Instant.ofEpochMilli(n.createdAt()).toString());
					JsonObject footer = new JsonObject();
					footer.addProperty("text", truncate("Sculkwire" + (n.ruleName().isBlank() ? "" : " · " + n.ruleName()), 200));
					embed.add("footer", footer);
					JsonArray embeds = new JsonArray();
					embeds.add(embed);
					o.add("embeds", embeds);
				}
				String target = url + (url.contains("?") ? "&" : "?") + "wait=true";
				return call("POST", target, Map.of("Content-Type", "application/json"), Json.COMPACT.toJson(o), Duration.ofSeconds(15));
			}
		};
	}
}
