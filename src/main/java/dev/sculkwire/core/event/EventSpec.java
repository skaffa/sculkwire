package dev.sculkwire.core.event;

import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.value.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Describes a game event: its payload, its trigger parameters (rendered as a form) and its filter. */
public record EventSpec(String id, String category, String description, List<PayloadVar> payload,
		List<FieldSpec> params, EventFilter filter) {

	public PayloadVar var(String name) {
		for (PayloadVar v : payload) {
			if (v.name().equals(name)) {
				return v;
			}
		}
		return null;
	}

	public Map<String, Value> samplePayload() {
		Map<String, Value> m = new LinkedHashMap<>();
		for (PayloadVar v : payload) {
			m.put(v.name(), v.sample());
		}
		return m;
	}
}
