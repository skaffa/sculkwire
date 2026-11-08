package dev.sculkwire.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex helpers with a time limit, so a catastrophic pattern from an imported rule cannot freeze the
 * game. Matching runs on a CharSequence that throws once its deadline passes.
 */
public final class SafeRegex {
	public static final long DEFAULT_TIMEOUT_MS = 50;
	private static final int CACHE_SIZE = 512;
	private static final Map<String, Pattern> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private SafeRegex() {
	}

	/** Compiles (cached). Throws {@link java.util.regex.PatternSyntaxException} for bad patterns. */
	public static Pattern compile(String regex, boolean ignoreCase) {
		String key = (ignoreCase ? "i:" : "s:") + regex;
		synchronized (CACHE) {
			Pattern p = CACHE.get(key);
			if (p == null) {
				p = Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
				CACHE.put(key, p);
			}
			return p;
		}
	}

	/** Finds the first match; empty when there is none or matching took too long. */
	public static Optional<MatchResult> find(Pattern p, String input) {
		return find(p, input, DEFAULT_TIMEOUT_MS);
	}

	public static Optional<MatchResult> find(Pattern p, String input, long timeoutMs) {
		try {
			Matcher m = p.matcher(new Deadline(input, System.nanoTime() + timeoutMs * 1_000_000L));
			return m.find() ? Optional.of(m.toMatchResult()) : Optional.empty();
		} catch (TimeoutException e) {
			return Optional.empty();
		}
	}

	/** Thrown internally when matching exceeds its time limit. */
	public static final class TimeoutException extends RuntimeException {
		TimeoutException() {
			super("regex took too long", null, false, false);
		}
	}

	private static final class Deadline implements CharSequence {
		private final CharSequence s;
		private final long deadline;
		private int calls;

		Deadline(CharSequence s, long deadline) {
			this.s = s;
			this.deadline = deadline;
		}

		@Override
		public char charAt(int index) {
			if ((++calls & 0xFF) == 0 && System.nanoTime() > deadline) {
				throw new TimeoutException();
			}
			return s.charAt(index);
		}

		@Override
		public int length() {
			return s.length();
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new Deadline(s.subSequence(start, end), deadline);
		}

		@Override
		public String toString() {
			return s.toString();
		}
	}
}
