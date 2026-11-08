package dev.sculkwire.core.model;

import dev.sculkwire.core.value.Op;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A condition tree node: either a {@code group} (op {@code all}, {@code any} or {@code none} over
 * children) or a {@code compare} (left operand, operator, right operand(s)).
 */
public final class Condition {
	public static final String GROUP = "group";
	public static final String COMPARE = "compare";

	public String type = COMPARE;
	public String op = "eq";
	public List<Condition> children;
	public Operand left;
	public Operand right;
	public Operand right2;
	public boolean ignoreCase;

	public static Condition group(String op, Condition... children) {
		Condition c = new Condition();
		c.type = GROUP;
		c.op = op;
		c.children = new ArrayList<>(Arrays.asList(children));
		return c;
	}

	public static Condition compare(Operand left, String op, Operand right) {
		Condition c = new Condition();
		c.type = COMPARE;
		c.left = left;
		c.op = op;
		c.right = right;
		return c;
	}

	public static Condition compare(Operand left, String op) {
		return compare(left, op, null);
	}

	public boolean isGroup() {
		return GROUP.equals(type);
	}

	public String describe() {
		if (isGroup()) {
			List<String> parts = new ArrayList<>();
			if (children != null) {
				for (Condition c : children) {
					parts.add(c.describe());
				}
			}
			String joiner = switch (op == null ? "all" : op) {
				case "any" -> " or ";
				case "none" -> " nor ";
				default -> " and ";
			};
			String inner = String.join(joiner, parts);
			if ("none".equals(op)) {
				return "not (" + inner + ")";
			}
			return parts.size() > 1 ? "(" + inner + ")" : inner;
		}
		StringBuilder sb = new StringBuilder(left == null ? "?" : left.describe()).append(' ');
		Op o = Op.parse(op);
		sb.append(o == null ? op : o.label());
		if (right != null) {
			sb.append(' ').append(right.describe());
		}
		if (right2 != null) {
			sb.append(" and ").append(right2.describe());
		}
		return sb.toString();
	}
}
