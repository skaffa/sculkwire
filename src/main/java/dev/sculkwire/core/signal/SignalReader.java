package dev.sculkwire.core.signal;

import dev.sculkwire.core.value.Value;

import java.util.Map;

/** Read access to signals; params may be raw (strings) and are resolved against the spec. */
public interface SignalReader {
	Value read(String id, Map<String, ?> params);

	SignalSpec spec(String id);
}
