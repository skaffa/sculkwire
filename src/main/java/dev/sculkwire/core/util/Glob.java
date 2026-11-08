package dev.sculkwire.core.util;

import java.util.Collection;
import java.util.regex.Pattern;

/** Case-insensitive wildcard matching: {@code *} any run of characters, {@code ?} one character. */
public final class Glob {
	private Glob() {
	}

	public static boolean matches(String glob, String text) {
		if (glob == null || text == null) {
			return false;
		}
		StringBuilder re = new StringBuilder();
		StringBuilder lit = new StringBuilder();
		for (char c : glob.trim().toCharArray()) {
			if (c == '*' || c == '?') {
				if (!lit.isEmpty()) {
					re.append(Pattern.quote(lit.toString()));
					lit.setLength(0);
				}
				re.append(c == '*' ? ".*" : ".");
			} else {
				lit.append(c);
			}
		}
		if (!lit.isEmpty()) {
			re.append(Pattern.quote(lit.toString()));
		}
		return SafeRegex.compile("^" + re + "$", true).matcher(text.trim()).matches();
	}

	public static boolean anyMatches(Collection<String> globs, String text) {
		for (String g : globs) {
			if (matches(g, text)) {
				return true;
			}
		}
		return false;
	}
}
