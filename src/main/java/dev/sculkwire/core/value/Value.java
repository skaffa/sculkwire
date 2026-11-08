package dev.sculkwire.core.value;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable typed value: what signals return, payloads carry and conditions compare. */
public final class Value {
	public static final Value NONE = new Value(ValueType.NONE, null);
	public static final Value TRUE = new Value(ValueType.BOOL, Boolean.TRUE);
	public static final Value FALSE = new Value(ValueType.BOOL, Boolean.FALSE);

	private final ValueType type;
	private final Object raw;

	private Value(ValueType type, Object raw) {
		this.type = type;
		this.raw = raw;
	}

	public static Value num(double d) {
		return Double.isNaN(d) ? NONE : new Value(ValueType.NUMBER, d);
	}

	public static Value bool(boolean b) {
		return b ? TRUE : FALSE;
	}

	public static Value str(String s) {
		return s == null ? NONE : new Value(ValueType.STRING, s);
	}

	public static Value enumOf(String s) {
		return s == null ? NONE : new Value(ValueType.ENUM, s.trim().toLowerCase(Locale.ROOT));
	}

	public static Value id(String s) {
		return s == null || s.isBlank() ? NONE : new Value(ValueType.ID, normalizeId(s));
	}

	public static Value list(Collection<String> items) {
		if (items == null) {
			return NONE;
		}
		List<String> copy = new ArrayList<>(items.size());
		for (String s : items) {
			if (s != null) {
				copy.add(s);
			}
		}
		return new Value(ValueType.LIST, Collections.unmodifiableList(copy));
	}

	/** Converts a plain Java object (Boolean, Number, Collection, Value or anything else as a string). */
	public static Value of(Object o) {
		if (o == null) {
			return NONE;
		}
		if (o instanceof Value v) {
			return v;
		}
		if (o instanceof Boolean b) {
			return bool(b);
		}
		if (o instanceof Number n) {
			return num(n.doubleValue());
		}
		if (o instanceof Collection<?> c) {
			List<String> l = new ArrayList<>(c.size());
			for (Object x : c) {
				l.add(x instanceof Value v ? v.asString() : String.valueOf(x));
			}
			return list(l);
		}
		return str(o.toString());
	}

	/** Lower-cases an id and adds the {@code minecraft:} namespace when missing. Tags keep their {@code #}. */
	public static String normalizeId(String s) {
		String t = s.trim().toLowerCase(Locale.ROOT);
		if (t.startsWith("#")) {
			return "#" + normalizeId(t.substring(1));
		}
		return t.isEmpty() || t.indexOf(':') >= 0 ? t : "minecraft:" + t;
	}

	public ValueType type() {
		return type;
	}

	public boolean isNone() {
		return type == ValueType.NONE;
	}

	public double asNumber() {
		return switch (type) {
			case NUMBER -> (Double) raw;
			case BOOL -> ((Boolean) raw) ? 1 : 0;
			case STRING, ENUM, ID -> parseDouble((String) raw);
			case LIST -> asList().size();
			case NONE -> Double.NaN;
		};
	}

	public boolean asBool() {
		return switch (type) {
			case BOOL -> (Boolean) raw;
			case NUMBER -> (Double) raw != 0;
			case STRING, ENUM, ID -> {
				String s = ((String) raw).trim().toLowerCase(Locale.ROOT);
				yield s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on");
			}
			case LIST -> !asList().isEmpty();
			case NONE -> false;
		};
	}

	public String asString() {
		return switch (type) {
			case NUMBER -> formatNumber((Double) raw);
			case BOOL -> raw.toString();
			case STRING, ENUM, ID -> (String) raw;
			case LIST -> String.join(", ", asList());
			case NONE -> "";
		};
	}

	@SuppressWarnings("unchecked")
	public List<String> asList() {
		return switch (type) {
			case LIST -> (List<String>) raw;
			case NONE -> List.of();
			default -> List.of(asString());
		};
	}

	/** Converts this value to another type; NONE stays NONE. */
	public Value coerce(ValueType target) {
		if (target == type || isNone() || target == null) {
			return this;
		}
		return switch (target) {
			case NUMBER -> num(asNumber());
			case BOOL -> bool(asBool());
			case STRING -> str(asString());
			case ENUM -> enumOf(asString());
			case ID -> id(asString());
			case LIST -> type == ValueType.STRING ? list(splitList(asString())) : list(asList());
			case NONE -> NONE;
		};
	}

	public JsonElement toJson() {
		return switch (type) {
			case NUMBER -> new JsonPrimitive((Double) raw);
			case BOOL -> new JsonPrimitive((Boolean) raw);
			case STRING, ENUM, ID -> new JsonPrimitive((String) raw);
			case LIST -> {
				JsonArray a = new JsonArray();
				asList().forEach(a::add);
				yield a;
			}
			case NONE -> JsonNull.INSTANCE;
		};
	}

	public static Value fromJson(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return NONE;
		}
		if (e.isJsonArray()) {
			List<String> l = new ArrayList<>();
			for (JsonElement x : e.getAsJsonArray()) {
				if (!x.isJsonNull()) {
					l.add(x.isJsonPrimitive() ? x.getAsString() : x.toString());
				}
			}
			return list(l);
		}
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (p.isBoolean()) {
				return bool(p.getAsBoolean());
			}
			if (p.isNumber()) {
				return num(p.getAsDouble());
			}
			return str(p.getAsString());
		}
		return str(e.toString());
	}

	/** Splits "a, b,c" into trimmed, non-empty parts. */
	public static List<String> splitList(String s) {
		List<String> out = new ArrayList<>();
		if (s == null) {
			return out;
		}
		for (String part : s.split(",")) {
			String t = part.trim();
			if (!t.isEmpty()) {
				out.add(t);
			}
		}
		return out;
	}

	public static String formatNumber(double d) {
		if (Double.isNaN(d)) {
			return "";
		}
		if (Double.isInfinite(d)) {
			return d > 0 ? "∞" : "-∞";
		}
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		BigDecimal bd = BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		return bd.toPlainString();
	}

	private static double parseDouble(String s) {
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Value v) || v.type != type) {
			return false;
		}
		if (type == ValueType.NUMBER) {
			return Double.compare((Double) raw, (Double) v.raw) == 0;
		}
		return Objects.equals(raw, v.raw);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, raw);
	}

	@Override
	public String toString() {
		return type.id() + ":" + asString();
	}
}
