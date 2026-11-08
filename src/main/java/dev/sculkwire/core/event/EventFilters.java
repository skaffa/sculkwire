package dev.sculkwire.core.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.sculkwire.core.engine.EvalContext;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.SafeRegex;
import dev.sculkwire.core.value.Ops;
import dev.sculkwire.core.value.Value;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Generic event filters that bind trigger parameters to payload values. Empty parameters match anything. */
public final class EventFilters {
	public enum Kind {
		/** Payload value is one of the listed values (ids, names; supports #tags and {list variables}). */
		ANY_OF,
		/** Regex found in the payload text; groups become match.N / match.name. */
		REGEX,
		/** Payload text contains the parameter text (case-insensitive). */
		CONTAINS,
		/** Payload number is at least the parameter. */
		MIN,
		/** Payload number is at most the parameter. */
		MAX
	}

	public record Binding(String param, String payloadVar, Kind kind) {
	}

	private EventFilters() {
	}

	public static Binding anyOf(String param, String payloadVar) {
		return new Binding(param, payloadVar, Kind.ANY_OF);
	}

	public static Binding regex(String param, String payloadVar) {
		return new Binding(param, payloadVar, Kind.REGEX);
	}

	public static Binding min(String param, String payloadVar) {
		return new Binding(param, payloadVar, Kind.MIN);
	}

	public static Binding max(String param, String payloadVar) {
		return new Binding(param, payloadVar, Kind.MAX);
	}

	public static EventFilter of(Binding... bindings) {
		List<Binding> list = List.of(bindings);
		return (params, ctx) -> {
			for (Binding b : list) {
				if (!check(b, params, ctx)) {
					return false;
				}
			}
			return true;
		};
	}

	private static boolean check(Binding b, JsonObject params, EvalContext ctx) {
		JsonElement e = params == null ? null : params.get(b.param());
		if (e == null || e.isJsonNull() || (e.isJsonPrimitive() && e.getAsString().isBlank())) {
			return true;
		}
		Value actual = ctx.payload(b.payloadVar());
		switch (b.kind()) {
			case ANY_OF: {
				List<String> items = ctx.resolveList(e);
				if (items.isEmpty()) {
					return true;
				}
				for (String item : items) {
					if (Ops.equalsLoose(actual, Value.str(item), true, ctx.tags())) {
						return true;
					}
				}
				return false;
			}
			case REGEX:
				return findInto(ctx.render(e.getAsString(), OutputContext.REGEX), actual.asString(), true, ctx);
			case CONTAINS:
				return actual.asString().toLowerCase(Locale.ROOT)
						.contains(ctx.render(e.getAsString(), OutputContext.PLAIN).toLowerCase(Locale.ROOT));
			case MIN:
			case MAX: {
				double limit;
				try {
					limit = e.getAsDouble();
				} catch (RuntimeException ex) {
					return true;
				}
				double x = actual.asNumber();
				if (Double.isNaN(x)) {
					return false;
				}
				return b.kind() == Kind.MIN ? x >= limit : x <= limit;
			}
			default:
				return true;
		}
	}

	/** Finds {@code regex} in {@code text}; on a match, stores groups as match.0..n and match.&lt;name&gt;. */
	public static boolean findInto(String regex, String text, boolean ignoreCase, EvalContext ctx) {
		Pattern p;
		try {
			p = SafeRegex.compile(regex, ignoreCase);
		} catch (PatternSyntaxException e) {
			return false;
		}
		Optional<MatchResult> m = SafeRegex.find(p, text);
		if (m.isEmpty()) {
			return false;
		}
		MatchResult r = m.get();
		for (int i = 0; i <= r.groupCount(); i++) {
			String g = r.group(i);
			ctx.putPayload("match." + i, Value.str(g == null ? "" : g));
		}
		try {
			for (Map.Entry<String, Integer> g : r.namedGroups().entrySet()) {
				String val = r.group(g.getValue());
				ctx.putPayload("match." + g.getKey(), Value.str(val == null ? "" : val));
			}
		} catch (UnsupportedOperationException ignored) {
			// named groups not available
		}
		return true;
	}
}
