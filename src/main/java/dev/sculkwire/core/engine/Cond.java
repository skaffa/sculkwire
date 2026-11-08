package dev.sculkwire.core.engine;

/** A compiled condition. */
@FunctionalInterface
public interface Cond {
	Cond TRUE = c -> true;
	Cond FALSE = c -> false;

	boolean test(EvalContext ctx);
}
