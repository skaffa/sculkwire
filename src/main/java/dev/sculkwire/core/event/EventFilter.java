package dev.sculkwire.core.event;

import com.google.gson.JsonObject;

import dev.sculkwire.core.engine.EvalContext;

/**
 * Decides whether a posted event matches a rule's trigger parameters. May add values to the payload
 * (e.g. regex groups as {@code match.1}).
 */
@FunctionalInterface
public interface EventFilter {
	EventFilter ANY = (params, ctx) -> true;

	boolean test(JsonObject params, EvalContext ctx);
}
