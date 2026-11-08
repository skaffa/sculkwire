package dev.sculkwire.core.config;

import com.google.gson.JsonObject;

import dev.sculkwire.core.model.SculkwireConfig;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Ordered config migrations: step N turns schema N into N+1. */
public final class Migrations {
	/** No migrations yet (schema 1 is the first). Add entries as {@code Map.entry(1, json -> ...)}. */
	private static final Map<Integer, UnaryOperator<JsonObject>> STEPS = Map.of();

	private Migrations() {
	}

	public static JsonObject migrate(JsonObject root, int from, List<String> warnings) {
		JsonObject o = root;
		for (int v = from; v < SculkwireConfig.SCHEMA_VERSION; v++) {
			UnaryOperator<JsonObject> step = STEPS.get(v);
			if (step == null) {
				warnings.add("No migration from config schema " + v + "; loading as is.");
				continue;
			}
			o = step.apply(o);
		}
		o.addProperty("schema_version", SculkwireConfig.SCHEMA_VERSION);
		return o;
	}
}
