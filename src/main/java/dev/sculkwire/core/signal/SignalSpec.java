package dev.sculkwire.core.signal;

import dev.sculkwire.core.value.ValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes a signal: a named, typed value the mod can read at any time. Specs are pure data (in the
 * core) so the UI, validation and tests can use them; the game-side code attaches a {@link SignalSource}.
 */
public record SignalSpec(String id, ValueType type, List<ParamSpec> params, String unit, List<String> enumOptions,
		String registryKind, int minIntervalTicks, Cost cost, String category, String description) {

	public enum Cost {
		CHEAP,
		MODERATE,
		/** Entity scans and the like: read at most every {@link #minIntervalTicks()} ticks. */
		EXPENSIVE
	}

	public ParamSpec param(String name) {
		for (ParamSpec p : params) {
			if (p.name().equals(name)) {
				return p;
			}
		}
		return null;
	}

	public static Builder builder(String id, ValueType type, String category, String description) {
		return new Builder(id, type, category, description);
	}

	public static final class Builder {
		private final String id;
		private final ValueType type;
		private final String category;
		private final String description;
		private final List<ParamSpec> params = new ArrayList<>();
		private String unit = "";
		private List<String> options = List.of();
		private String registry;
		private int minInterval = 1;
		private Cost cost = Cost.CHEAP;

		private Builder(String id, ValueType type, String category, String description) {
			this.id = id;
			this.type = type;
			this.category = category;
			this.description = description;
		}

		public Builder unit(String u) {
			unit = u;
			return this;
		}

		public Builder options(String... o) {
			options = List.of(o);
			return this;
		}

		public Builder registry(String r) {
			registry = r;
			return this;
		}

		public Builder param(ParamSpec p) {
			params.add(p);
			return this;
		}

		public Builder expensive(int minIntervalTicks) {
			cost = Cost.EXPENSIVE;
			minInterval = minIntervalTicks;
			return this;
		}

		public Builder moderate() {
			cost = Cost.MODERATE;
			return this;
		}

		public SignalSpec build() {
			return new SignalSpec(id, type, List.copyOf(params), unit, options, registry, minInterval, cost, category, description);
		}
	}
}
