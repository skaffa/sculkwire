package dev.sculkwire.core.util;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes webhook tokens, bot tokens and other secrets from text before it is logged or shown. */
public final class Redact {
	private static final Pattern DISCORD = Pattern.compile("(discord(?:app)?\\.com/api/webhooks/\\d+/)[\\w-]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern TELEGRAM = Pattern.compile("(api\\.telegram\\.org/bot)\\d+:[\\w-]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern NTFY_TOKEN = Pattern.compile("\\btk_[A-Za-z0-9]{8,}");
	private static final Pattern QUERY = Pattern.compile("([?&](?:token|key|apikey|api_key|access_token|secret)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)\\S+", Pattern.CASE_INSENSITIVE);

	private Redact() {
	}

	public static String text(String s) {
		if (s == null || s.isEmpty()) {
			return s;
		}
		String out = DISCORD.matcher(s).replaceAll("$1****");
		out = TELEGRAM.matcher(out).replaceAll("$1****");
		out = NTFY_TOKEN.matcher(out).replaceAll("tk_****");
		out = QUERY.matcher(out).replaceAll("$1****");
		out = BEARER.matcher(out).replaceAll("$1****");
		return out;
	}

	/** Also replaces every literal secret value (6+ characters) that appears in the text. */
	public static String text(String s, Collection<String> secrets) {
		String out = text(s);
		if (out == null || secrets == null) {
			return out;
		}
		for (String secret : secrets) {
			if (secret != null && secret.length() >= 6) {
				out = out.replace(secret, "****");
			}
		}
		return out;
	}

	/** Masks a value for display: keeps the last 4 characters of long values. */
	public static String mask(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		if (value.length() <= 8) {
			return "****";
		}
		return "****" + value.substring(value.length() - 4);
	}

	static String quote(String s) {
		return Matcher.quoteReplacement(s);
	}
}
