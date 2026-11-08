package dev.sculkwire.core.signal;

import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import java.util.List;

/** A parameter of a signal, e.g. {@code radius} of {@code nearby.hostiles}. */
public record ParamSpec(String name, ValueType type, Value defaultValue, double min, double max, String registryKind,
		List<String> options, String label) {

	public static ParamSpec number(String name, double def, double min, double max, String label) {
		return new ParamSpec(name, ValueType.NUMBER, Value.num(def), min, max, null, List.of(), label);
	}

	public static ParamSpec id(String name, String registry, String def, String label) {
		return new ParamSpec(name, ValueType.ID, def == null || def.isEmpty() ? Value.NONE : Value.id(def),
				Double.NaN, Double.NaN, registry, List.of(), label);
	}

	public static ParamSpec enumOf(String name, String def, String label, String... options) {
		return new ParamSpec(name, ValueType.ENUM, Value.enumOf(def), Double.NaN, Double.NaN, null, List.of(options), label);
	}

	public static ParamSpec string(String name, String def, String label) {
		return new ParamSpec(name, ValueType.STRING, Value.str(def), Double.NaN, Double.NaN, null, List.of(), label);
	}

	/** Coerces and clamps a given value (or the default when absent). */
	public Value resolve(Value given) {
		Value v = given == null || given.isNone() || given.asString().isEmpty() ? defaultValue : given.coerce(type);
		if (type == ValueType.NUMBER && !v.isNone()) {
			double d = v.asNumber();
			if (!Double.isNaN(min)) {
				d = Math.max(min, d);
			}
			if (!Double.isNaN(max)) {
				d = Math.min(max, d);
			}
			v = Value.num(d);
		}
		if (type == ValueType.ENUM && !options.isEmpty() && !v.isNone() && !options.contains(v.asString())) {
			v = defaultValue;
		}
		return v;
	}
}
