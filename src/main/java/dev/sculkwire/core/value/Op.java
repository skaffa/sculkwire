package dev.sculkwire.core.value;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Comparison operators for conditions. {@link #arity()} is the number of right-hand operands. */
public enum Op {
	LT("lt", 1, "<"),
	LTE("lte", 1, "≤"),
	EQ("eq", 1, "="),
	NEQ("neq", 1, "≠"),
	GTE("gte", 1, "≥"),
	GT("gt", 1, ">"),
	BETWEEN("between", 2, "between"),
	OUTSIDE("outside", 2, "outside"),
	CONTAINS("contains", 1, "contains"),
	NOT_CONTAINS("not_contains", 1, "does not contain"),
	STARTS_WITH("starts_with", 1, "starts with"),
	ENDS_WITH("ends_with", 1, "ends with"),
	MATCHES("matches", 1, "matches regex"),
	IN("in", 1, "is one of"),
	NOT_IN("not_in", 1, "is not one of"),
	IS_TRUE("is_true", 0, "is true"),
	IS_FALSE("is_false", 0, "is false"),
	CONTAINS_ANY("contains_any", 1, "contains any of"),
	SIZE_LT("size_lt", 1, "size <"),
	SIZE_GT("size_gt", 1, "size >"),
	SIZE_EQ("size_eq", 1, "size ="),
	IS_EMPTY("is_empty", 0, "is empty"),
	IN_TAG("in_tag", 1, "is in tag"),
	IS_AVAILABLE("is_available", 0, "is available"),
	/** True when the value differs from the previous evaluation. Only meaningful in condition triggers. */
	CHANGED("changed", 0, "changed");

	private final String id;
	private final int arity;
	private final String label;

	Op(String id, int arity, String label) {
		this.id = id;
		this.arity = arity;
		this.label = label;
	}

	public String id() {
		return id;
	}

	public int arity() {
		return arity;
	}

	public String label() {
		return label;
	}

	public static Op parse(String id) {
		if (id == null) {
			return null;
		}
		String t = id.trim().toLowerCase(Locale.ROOT);
		for (Op op : values()) {
			if (op.id.equals(t)) {
				return op;
			}
		}
		return switch (t) {
			case "<" -> LT;
			case "<=" -> LTE;
			case "=", "==" -> EQ;
			case "!=" -> NEQ;
			case ">=" -> GTE;
			case ">" -> GT;
			default -> null;
		};
	}

	/** Operators that make sense for a left operand of the given type (NONE = unknown type: all). */
	public static List<Op> forType(ValueType t) {
		EnumSet<Op> set = switch (t) {
			case NUMBER -> EnumSet.of(LT, LTE, EQ, NEQ, GTE, GT, BETWEEN, OUTSIDE);
			case STRING -> EnumSet.of(EQ, NEQ, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, IN, NOT_IN, IS_EMPTY);
			case BOOL -> EnumSet.of(IS_TRUE, IS_FALSE, EQ);
			case LIST -> EnumSet.of(CONTAINS, NOT_CONTAINS, CONTAINS_ANY, SIZE_LT, SIZE_GT, SIZE_EQ, IS_EMPTY);
			case ENUM -> EnumSet.of(EQ, NEQ, IN, NOT_IN);
			case ID -> EnumSet.of(EQ, NEQ, IN, NOT_IN, IN_TAG);
			case NONE -> EnumSet.allOf(Op.class);
		};
		set.add(IS_AVAILABLE);
		set.add(CHANGED);
		return List.copyOf(set);
	}
}
