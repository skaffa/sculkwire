package dev.sculkwire.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One block of an event's script. Blocks run top to bottom:
 * <ul>
 * <li>{@code if}: starts a chain; runs its sends when {@code when} is true.</li>
 * <li>{@code else_if}: runs when no earlier block of its chain ran and {@code when} is true.</li>
 * <li>{@code else}: runs when no earlier block of its chain ran; ends the chain.</li>
 * <li>{@code do}: plain sends that always run (they also end a chain).</li>
 * </ul>
 * Older configs have no {@code type}: the first conditional block is {@code if}, later ones {@code else_if},
 * and a block without a condition is {@code else} (or {@code do} when it is the only block).
 */
public final class Branch {
	public static final String IF = "if";
	public static final String ELSE_IF = "else_if";
	public static final String ELSE = "else";
	public static final String DO = "do";

	public String type;
	public Condition when;
	public List<Send> sends = new ArrayList<>();

	public static Branch of(Condition when, Send... sends) {
		Branch b = new Branch();
		b.when = when;
		b.sends = new ArrayList<>(List.of(sends));
		return b;
	}

	public static Branch typed(String type, Condition when, Send... sends) {
		Branch b = of(when, sends);
		b.type = type;
		return b;
	}

	/** The block type, derived for older configs that have none. */
	public static String typeOf(List<Branch> blocks, int index) {
		Branch b = blocks.get(index);
		if (b.type != null && !b.type.isBlank()) {
			return b.type;
		}
		if (b.when != null) {
			return index == 0 ? IF : ELSE_IF;
		}
		return blocks.size() == 1 ? DO : ELSE;
	}

	public boolean conditional() {
		return IF.equals(type) || ELSE_IF.equals(type);
	}
}
