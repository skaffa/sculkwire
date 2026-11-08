package dev.sculkwire.core.template;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Escapes substituted values for an {@link OutputContext}. */
public final class Escaper {
	private Escaper() {
	}

	public static String escape(String s, OutputContext ctx) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		return switch (ctx) {
			case PLAIN -> s;
			case JSON_STRING -> json(s);
			case URL_COMPONENT -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
			case FORM_COMPONENT -> URLEncoder.encode(s, StandardCharsets.UTF_8);
			case HEADER -> header(s);
			case DISCORD_MARKDOWN -> discord(s);
			case TELEGRAM_HTML -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
			case REGEX -> Pattern.quote(s);
		};
	}

	public static String json(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20 || c == ' ' || c == ' ') {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}

	private static String header(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
		}
		return sb.toString().trim();
	}

	private static String discord(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ("\\*_~`|>[]".indexOf(c) >= 0) {
				sb.append('\\');
			}
			sb.append(c);
			if (c == '@') {
				sb.append('​'); // breaks @everyone / @here / <@id>
			}
		}
		return sb.toString();
	}
}
