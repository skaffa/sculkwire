package dev.sculkwire.core.value;

import dev.sculkwire.core.util.SafeRegex;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates {@link Op}s. Any comparison with a NONE operand is false (except {@link Op#IS_AVAILABLE}). */
public final class Ops {
	private static final double EPS = 1e-9;

	private Ops() {
	}

	public static boolean test(Op op, Value left, Value a, Value b, boolean ignoreCase, TagResolver tags) {
		if (tags == null) {
			tags = TagResolver.NONE;
		}
		if (op == Op.IS_AVAILABLE) {
			return !left.isNone();
		}
		if (left.isNone()) {
			return false;
		}
		switch (op) {
			case IS_TRUE:
				return left.asBool();
			case IS_FALSE:
				return !left.asBool();
			case IS_EMPTY:
				return left.type() == ValueType.LIST ? left.asList().isEmpty() : left.asString().isEmpty();
			case CHANGED:
				throw new IllegalStateException("'changed' is evaluated by the rule engine");
			default:
				break;
		}
		if (a == null || a.isNone()) {
			return false;
		}
		return switch (op) {
			case LT -> numeric(left, a, (x, y) -> x < y);
			case LTE -> numeric(left, a, (x, y) -> x <= y + EPS);
			case GT -> numeric(left, a, (x, y) -> x > y);
			case GTE -> numeric(left, a, (x, y) -> x + EPS >= y);
			case EQ -> equalsLoose(left, a, ignoreCase, tags);
			case NEQ -> !equalsLoose(left, a, ignoreCase, tags);
			case BETWEEN -> between(left, a, b);
			case OUTSIDE -> b != null && !b.isNone() && !Double.isNaN(left.asNumber()) && !between(left, a, b);
			case CONTAINS -> contains(left, a.asString(), ignoreCase);
			case NOT_CONTAINS -> !contains(left, a.asString(), ignoreCase);
			case STARTS_WITH -> fold(left.asString(), ignoreCase).startsWith(fold(a.asString(), ignoreCase));
			case ENDS_WITH -> fold(left.asString(), ignoreCase).endsWith(fold(a.asString(), ignoreCase));
			case MATCHES -> matches(left.asString(), a.asString(), ignoreCase);
			case IN -> in(left, a, ignoreCase, tags);
			case NOT_IN -> !in(left, a, ignoreCase, tags);
			case CONTAINS_ANY -> containsAny(left, a, ignoreCase);
			case SIZE_LT -> size(left) < a.asNumber();
			case SIZE_GT -> size(left) > a.asNumber();
			case SIZE_EQ -> Math.abs(size(left) - a.asNumber()) < EPS;
			case IN_TAG -> {
				String tag = a.asString().startsWith("#") ? a.asString() : "#" + a.asString();
				yield tags.inTag(Value.normalizeId(left.asString()), Value.normalizeId(tag));
			}
			default -> false;
		};
	}

	/** Loose equality: numbers by value, ids with namespace normalization and tags, lists as sets. */
	public static boolean equalsLoose(Value left, Value right, boolean ignoreCase, TagResolver tags) {
		if (left.isNone() || right.isNone()) {
			return false;
		}
		if (left.type() == ValueType.NUMBER || right.type() == ValueType.NUMBER) {
			double x = left.asNumber();
			double y = right.asNumber();
			if (!Double.isNaN(x) && !Double.isNaN(y)) {
				return Math.abs(x - y) < EPS;
			}
		}
		if (left.type() == ValueType.BOOL || right.type() == ValueType.BOOL) {
			return left.asBool() == right.asBool();
		}
		if (left.type() == ValueType.ID || right.type() == ValueType.ID) {
			String r = right.asString().trim();
			String l = left.asString().trim();
			if (r.startsWith("#")) {
				return tags != null && tags.inTag(Value.normalizeId(l), Value.normalizeId(r));
			}
			return Value.normalizeId(l).equals(Value.normalizeId(r));
		}
		if (left.type() == ValueType.LIST && right.type() == ValueType.LIST) {
			List<String> x = left.asList();
			List<String> y = right.asList();
			if (x.size() != y.size()) {
				return false;
			}
			for (String s : x) {
				if (y.stream().noneMatch(t -> itemEquals(s, t, ignoreCase))) {
					return false;
				}
			}
			return true;
		}
		return ignoreCase || left.type() == ValueType.ENUM
				? left.asString().equalsIgnoreCase(right.asString())
				: left.asString().equals(right.asString());
	}

	/** List item equality: case rules, and ids compared with/without the minecraft namespace. */
	public static boolean itemEquals(String a, String b, boolean ignoreCase) {
		if (ignoreCase ? a.equalsIgnoreCase(b) : a.equals(b)) {
			return true;
		}
		if (a.indexOf(':') >= 0 || b.indexOf(':') >= 0) {
			return Value.normalizeId(a).equals(Value.normalizeId(b));
		}
		return false;
	}

	public static List<String> listOf(Value v) {
		return v.type() == ValueType.LIST ? v.asList() : Value.splitList(v.asString());
	}

	public static boolean matches(String text, String regex, boolean ignoreCase) {
		try {
			Pattern p = SafeRegex.compile(regex, ignoreCase);
			return SafeRegex.find(p, text).isPresent();
		} catch (PatternSyntaxException e) {
			return false;
		}
	}

	private static boolean in(Value left, Value list, boolean ignoreCase, TagResolver tags) {
		for (String item : listOf(list)) {
			if (equalsLoose(left, Value.str(item), ignoreCase, tags)) {
				return true;
			}
		}
		return false;
	}

	private static boolean contains(Value left, String needle, boolean ignoreCase) {
		if (left.type() == ValueType.LIST) {
			return left.asList().stream().anyMatch(s -> itemEquals(s, needle, ignoreCase));
		}
		return fold(left.asString(), ignoreCase).contains(fold(needle, ignoreCase));
	}

	private static boolean containsAny(Value left, Value items, boolean ignoreCase) {
		for (String item : listOf(items)) {
			if (contains(left, item, ignoreCase)) {
				return true;
			}
		}
		return false;
	}

	private static double size(Value v) {
		return v.type() == ValueType.LIST ? v.asList().size() : v.asString().length();
	}

	private static boolean between(Value left, Value a, Value b) {
		if (b == null || b.isNone()) {
			return false;
		}
		double x = left.asNumber();
		double lo = Math.min(a.asNumber(), b.asNumber());
		double hi = Math.max(a.asNumber(), b.asNumber());
		if (Double.isNaN(x) || Double.isNaN(lo) || Double.isNaN(hi)) {
			return false;
		}
		return x + EPS >= lo && x <= hi + EPS;
	}

	private interface DoubleCmp {
		boolean test(double x, double y);
	}

	private static boolean numeric(Value left, Value right, DoubleCmp cmp) {
		double x = left.asNumber();
		double y = right.asNumber();
		return !Double.isNaN(x) && !Double.isNaN(y) && cmp.test(x, y);
	}

	private static String fold(String s, boolean ignoreCase) {
		return ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
	}
}
