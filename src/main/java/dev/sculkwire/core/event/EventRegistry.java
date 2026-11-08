package dev.sculkwire.core.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All known events. */
public final class EventRegistry {
	private final Map<String, EventSpec> specs = new LinkedHashMap<>();

	public void define(EventSpec spec) {
		specs.put(spec.id(), spec);
	}

	public EventSpec get(String id) {
		return id == null ? null : specs.get(id);
	}

	public Collection<EventSpec> all() {
		return Collections.unmodifiableCollection(specs.values());
	}

	public List<String> categories() {
		List<String> out = new ArrayList<>();
		for (EventSpec s : specs.values()) {
			if (!out.contains(s.category())) {
				out.add(s.category());
			}
		}
		return out;
	}
}
