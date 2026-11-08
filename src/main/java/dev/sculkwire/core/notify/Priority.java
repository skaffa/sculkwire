package dev.sculkwire.core.notify;

import java.util.Locale;

/** Notification priority; each notifier maps it to its service's scale. */
public enum Priority {
	MIN,
	LOW,
	DEFAULT,
	HIGH,
	URGENT;

	public static Priority parse(String s) {
		if (s == null || s.isBlank()) {
			return DEFAULT;
		}
		try {
			return valueOf(s.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return switch (s.trim()) {
				case "1" -> MIN;
				case "2" -> LOW;
				case "4" -> HIGH;
				case "5", "max" -> URGENT;
				default -> DEFAULT;
			};
		}
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** ntfy priority 1–5. */
	public int ntfy() {
		return ordinal() + 1;
	}
}
