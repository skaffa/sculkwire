package dev.sculkwire.core.event;

import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

/** A value an event carries, e.g. {@code msg.sender}. The sample is used for previews and test fires. */
public record PayloadVar(String name, ValueType type, Value sample, String description) {
	public static PayloadVar of(String name, ValueType type, Object sample, String description) {
		Value v = Value.of(sample);
		return new PayloadVar(name, type, v.isNone() ? v : v.coerce(type), description);
	}
}
