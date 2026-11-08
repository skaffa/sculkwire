package dev.sculkwire.core.signal;

import dev.sculkwire.core.value.Value;

import java.util.Map;

/** Reads a signal's current value. Called on the game thread only. Return {@link Value#NONE} when unavailable. */
@FunctionalInterface
public interface SignalSource {
	Value read(Map<String, Value> params);
}
