package dev.sculkwire.core.signal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** A signal id plus raw parameter values, written as {@code nearby.hostiles(radius=16)}. */
public record SignalRef(String id, Map<String, String> params) {
	public SignalRef {
		params = params == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(params));
	}

	public static SignalRef of(String id) {
		return new SignalRef(id, Map.of());
	}

	/** Parses {@code id} or {@code id(k=v, k2=v2)}. Returns null for blank input. */
	public static SignalRef parse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String t = text.trim();
		int p = t.indexOf('(');
		if (p < 0 || !t.endsWith(")")) {
			return new SignalRef(t, Map.of());
		}
		Map<String, String> params = new LinkedHashMap<>();
		for (String kv : t.substring(p + 1, t.length() - 1).split(",")) {
			int eq = kv.indexOf('=');
			if (eq > 0) {
				String v = kv.substring(eq + 1).trim();
				if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
					v = v.substring(1, v.length() - 1);
				}
				params.put(kv.substring(0, eq).trim(), v);
			}
		}
		return new SignalRef(t.substring(0, p).trim(), params);
	}

	@Override
	public String toString() {
		if (params.isEmpty()) {
			return id;
		}
		StringBuilder sb = new StringBuilder(id).append('(');
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) {
				sb.append(", ");
			}
			first = false;
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.append(')').toString();
	}
}
