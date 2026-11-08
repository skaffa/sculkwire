package dev.sculkwire.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One step of an if / else-if / else chain. The engine runs the first branch whose {@code when} is true;
 * a branch without {@code when} is the "else" (or "always" when it is the only one).
 */
public final class Branch {
	public Condition when;
	public List<Send> sends = new ArrayList<>();

	public static Branch of(Condition when, Send... sends) {
		Branch b = new Branch();
		b.when = when;
		b.sends = new ArrayList<>(List.of(sends));
		return b;
	}
}
