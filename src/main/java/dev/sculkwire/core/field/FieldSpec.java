package dev.sculkwire.core.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.sculkwire.core.template.OutputContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes one form field. The UI renders any {@code List<FieldSpec>} with one generic form widget,
 * so notifier types, event parameters and signal parameters need no screen code of their own.
 */
public record FieldSpec(String key, FieldKind kind, String label, String hint, boolean required, boolean secret,
		JsonElement defaultValue, List<String> options, String registryKind, OutputContext context) {

	public static FieldSpec of(String key, FieldKind kind, String label) {
		return new FieldSpec(key, kind, label, "", false, kind == FieldKind.SECRET, null, List.of(), null, OutputContext.PLAIN);
	}

	public FieldSpec withHint(String h) {
		return new FieldSpec(key, kind, label, h, required, secret, defaultValue, options, registryKind, context);
	}

	public FieldSpec asRequired() {
		return new FieldSpec(key, kind, label, hint, true, secret, defaultValue, options, registryKind, context);
	}

	public FieldSpec asSecret() {
		return new FieldSpec(key, kind, label, hint, required, true, defaultValue, options, registryKind, context);
	}

	public FieldSpec withDefault(Object def) {
		JsonElement d;
		if (def == null) {
			d = null;
		} else if (def instanceof JsonElement je) {
			d = je;
		} else if (def instanceof Boolean b) {
			d = new JsonPrimitive(b);
		} else if (def instanceof Number n) {
			d = new JsonPrimitive(n);
		} else if (def instanceof List<?> l) {
			JsonArray a = new JsonArray();
			l.forEach(x -> a.add(String.valueOf(x)));
			d = a;
		} else {
			d = new JsonPrimitive(def.toString());
		}
		return new FieldSpec(key, kind, label, hint, required, secret, d, options, registryKind, context);
	}

	public FieldSpec withOptions(String... opts) {
		return new FieldSpec(key, kind, label, hint, required, secret, defaultValue, List.of(opts), registryKind, context);
	}

	public FieldSpec withRegistry(String registry) {
		return new FieldSpec(key, kind, label, hint, required, secret, defaultValue, options, registry, context);
	}

	public FieldSpec withContext(OutputContext ctx) {
		return new FieldSpec(key, kind, label, hint, required, secret, defaultValue, options, registryKind, ctx);
	}

	// ---- reading values from a settings/params object ----

	public JsonElement raw(JsonObject o) {
		JsonElement e = o == null ? null : o.get(key);
		return e == null || e.isJsonNull() ? defaultValue : e;
	}

	public String string(JsonObject o) {
		JsonElement e = raw(o);
		if (e == null || e.isJsonNull()) {
			return "";
		}
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	public boolean bool(JsonObject o) {
		JsonElement e = raw(o);
		if (e == null || !e.isJsonPrimitive()) {
			return false;
		}
		return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : Boolean.parseBoolean(e.getAsString());
	}

	public double number(JsonObject o, double def) {
		JsonElement e = raw(o);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		try {
			return e.getAsDouble();
		} catch (NumberFormatException ex) {
			return def;
		}
	}

	public List<String> list(JsonObject o) {
		JsonElement e = raw(o);
		List<String> out = new ArrayList<>();
		if (e == null || e.isJsonNull()) {
			return out;
		}
		if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				if (x.isJsonPrimitive() && !x.getAsString().isBlank()) {
					out.add(x.getAsString().trim());
				}
			}
			return out;
		}
		for (String s : e.getAsString().split(",")) {
			if (!s.isBlank()) {
				out.add(s.trim());
			}
		}
		return out;
	}

	public Map<String, String> map(JsonObject o) {
		JsonElement e = raw(o);
		Map<String, String> out = new LinkedHashMap<>();
		if (e != null && e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				if (!en.getValue().isJsonNull()) {
					out.put(en.getKey(), en.getValue().isJsonPrimitive() ? en.getValue().getAsString() : en.getValue().toString());
				}
			}
		}
		return out;
	}
}
