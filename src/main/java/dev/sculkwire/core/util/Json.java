package dev.sculkwire.core.util;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Shared Gson setup: snake_case JSON keys for camelCase Java fields. */
public final class Json {
	public static final Gson GSON = base().setPrettyPrinting().create();
	public static final Gson COMPACT = base().create();

	private Json() {
	}

	private static GsonBuilder base() {
		return new GsonBuilder()
				.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
				.disableHtmlEscaping();
	}

	/** Deep copy through JSON. */
	public static <T> T copy(T obj, Class<T> type) {
		return GSON.fromJson(GSON.toJsonTree(obj), type);
	}

	public static String str(JsonObject o, String key, String def) {
		if (o == null) {
			return def;
		}
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return def;
		}
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	public static boolean bool(JsonObject o, String key, boolean def) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : Boolean.parseBoolean(e.getAsString());
	}

	public static double num(JsonObject o, String key, double def) {
		JsonElement e = o == null ? null : o.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			return e.getAsDouble();
		} catch (NumberFormatException ex) {
			return def;
		}
	}
}
