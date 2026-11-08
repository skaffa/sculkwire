package dev.sculkwire.core.value;

import java.util.Locale;

/** The kinds of values signals, payloads, variables and literals can hold. */
public enum ValueType {
	NUMBER,
	BOOL,
	STRING,
	LIST,
	/** A string from a fixed set of options (the UI shows a dropdown). */
	ENUM,
	/** A namespaced resource id such as {@code minecraft:the_nether}; supports tags ({@code #minecraft:logs}). */
	ID,
	/** Not available (e.g. a player signal while not in a world). */
	NONE;

	public static ValueType parse(String s) {
		if (s == null) {
			return STRING;
		}
		try {
			return valueOf(s.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return STRING;
		}
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
