package dev.sculkwire.core.template;

import dev.sculkwire.core.value.Value;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Values captured on the game thread at the moment a rule fires, so templates can be rendered
 * later on the dispatch thread (with each notifier's own escaping) without touching the game.
 */
public final class Snapshot implements VariableResolver {
	private final Map<String, Value> values;

	private Snapshot(Map<String, Value> values) {
		this.values = values;
	}

	public static Snapshot capture(Collection<Template> templates, VariableResolver live) {
		Map<String, Value> map = new HashMap<>();
		for (Template t : templates) {
			if (t == null) {
				continue;
			}
			for (Template.Expr e : t.expressions()) {
				map.computeIfAbsent(e.key(), k -> {
					Value v = live.resolve(e.name(), e.params());
					return v == null ? Value.NONE : v;
				});
			}
		}
		return new Snapshot(Map.copyOf(map));
	}

	public static Snapshot of(Map<String, Value> values) {
		return new Snapshot(Map.copyOf(values));
	}

	@Override
	public Value resolve(String name, Map<String, String> params) {
		return values.getOrDefault(Template.key(name, params), Value.NONE);
	}

	public Map<String, Value> values() {
		return values;
	}
}
