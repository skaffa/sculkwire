package dev.sculkwire.core.event;

import com.google.gson.JsonObject;

import dev.sculkwire.core.engine.EvalContext;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.Json;
import dev.sculkwire.core.util.SafeRegex;
import dev.sculkwire.core.value.Value;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Filter for {@code chat.message}: source (player/system), own messages, sender list, sender extraction
 * from system messages (plugin servers), and the match mode (contains/equals/regex/mentions_me).
 */
public final class ChatFilter implements EventFilter {
	@Override
	public boolean test(JsonObject p, EvalContext ctx) {
		String kind = ctx.payload("msg.kind").asString();
		String source = Json.str(p, "source", "both");
		if (source.equals("player") && !kind.equals("player")) {
			return false;
		}
		if (source.equals("system") && !kind.equals("system")) {
			return false;
		}
		boolean ignoreCase = Json.bool(p, "ignore_case", true);
		String me = ctx.signal("player.name", Map.of()).asString();

		String senderRegex = Json.str(p, "sender_regex", "");
		if (!senderRegex.isBlank() && !kind.equals("player")) {
			extractSender(senderRegex, ctx, me);
		}
		ctx.putPayload("msg.has_sender", Value.bool(!ctx.payload("msg.sender").asString().isBlank()));
		if (Json.bool(p, "ignore_own", true) && ctx.payload("msg.is_own").asBool()) {
			return false;
		}

		List<String> senders = ctx.resolveList(p == null ? null : p.get("senders"));
		if (!senders.isEmpty()) {
			String s = ctx.payload("msg.sender").asString();
			if (senders.stream().noneMatch(x -> x.equalsIgnoreCase(s))) {
				return false;
			}
		}

		String text = ctx.payload("msg.text").asString();
		String pattern = Json.str(p, "pattern", "");
		String mode = Json.str(p, "mode", "any");
		switch (mode) {
			case "contains": {
				if (pattern.isBlank()) {
					return true;
				}
				String needle = ctx.render(pattern, OutputContext.PLAIN);
				return ignoreCase ? text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)) : text.contains(needle);
			}
			case "equals": {
				String want = ctx.render(pattern, OutputContext.PLAIN).trim();
				return ignoreCase ? text.trim().equalsIgnoreCase(want) : text.trim().equals(want);
			}
			case "regex":
				return pattern.isBlank() || EventFilters.findInto(ctx.render(pattern, OutputContext.REGEX), text, ignoreCase, ctx);
			case "mentions_me": {
				if (me.isBlank() || me.equalsIgnoreCase(ctx.payload("msg.sender").asString())) {
					return false;
				}
				String re = "(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])";
				return EventFilters.findInto(re, text, true, ctx);
			}
			default:
				return true;
		}
	}

	private static void extractSender(String regex, EvalContext ctx, String me) {
		Pattern p;
		try {
			p = SafeRegex.compile(regex, false);
		} catch (PatternSyntaxException e) {
			return;
		}
		Optional<MatchResult> m = SafeRegex.find(p, ctx.payload("msg.text").asString());
		if (m.isEmpty()) {
			return;
		}
		Map<String, Integer> named;
		try {
			named = m.get().namedGroups();
		} catch (UnsupportedOperationException e) {
			return;
		}
		if (named.containsKey("name") && m.get().group(named.get("name")) != null) {
			String sender = m.get().group(named.get("name"));
			ctx.putPayload("msg.sender", Value.str(sender));
			if (!me.isBlank() && sender.equalsIgnoreCase(me)) {
				ctx.putPayload("msg.is_own", Value.TRUE);
			}
		}
		if (named.containsKey("msg") && m.get().group(named.get("msg")) != null) {
			ctx.putPayload("msg.text", Value.str(m.get().group(named.get("msg"))));
		}
	}
}
