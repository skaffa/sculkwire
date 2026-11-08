package dev.sculkwire.core.template;

import dev.sculkwire.core.value.Value;

import java.util.Map;

/** Looks up {@code {name(params)}} for templates. Unknown names return {@link Value#NONE}. */
@FunctionalInterface
public interface VariableResolver {
	VariableResolver EMPTY = (name, params) -> Value.NONE;

	Value resolve(String name, Map<String, String> params);
}
