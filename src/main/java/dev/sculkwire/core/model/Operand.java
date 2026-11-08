package dev.sculkwire.core.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One side of a comparison. Exactly one of the fields is set: a signal (with optional params), an event
 * payload value, a user variable, a literal value, or a template string.
 */
public final class Operand {
	public String signal;
	public Map<String, JsonElement> params;
	public String payload;
	public String var;
	public JsonElement value;
	public String template;

	public static Operand signal(String id) {
		Operand o = new Operand();
		o.signal = id;
		return o;
	}

	public static Operand signal(String id, String param, Object v) {
		Operand o = signal(id);
		o.params = new LinkedHashMap<>();
		o.params.put(param, v instanceof Number n ? new JsonPrimitive(n) : new JsonPrimitive(String.valueOf(v)));
		return o;
	}

	public static Operand payload(String name) {
		Operand o = new Operand();
		o.payload = name;
		return o;
	}

	public static Operand var(String name) {
		Operand o = new Operand();
		o.var = name;
		return o;
	}

	public static Operand value(Object v) {
		Operand o = new Operand();
		if (v instanceof JsonElement je) {
			o.value = je;
		} else if (v instanceof Number n) {
			o.value = new JsonPrimitive(n);
		} else if (v instanceof Boolean b) {
			o.value = new JsonPrimitive(b);
		} else {
			o.value = new JsonPrimitive(String.valueOf(v));
		}
		return o;
	}

	public static Operand template(String t) {
		Operand o = new Operand();
		o.template = t;
		return o;
	}

	public boolean isEmpty() {
		return signal == null && payload == null && var == null && value == null && template == null;
	}

	/** Short human-readable form for lists and debug output. */
	public String describe() {
		if (signal != null) {
			if (params == null || params.isEmpty()) {
				return signal;
			}
			StringBuilder sb = new StringBuilder(signal).append('(');
			boolean first = true;
			for (Map.Entry<String, JsonElement> e : params.entrySet()) {
				if (!first) {
					sb.append(", ");
				}
				first = false;
				sb.append(e.getKey()).append('=').append(e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue());
			}
			return sb.append(')').toString();
		}
		if (payload != null) {
			return payload;
		}
		if (var != null) {
			return "var." + var;
		}
		if (template != null) {
			return "\"" + template + "\"";
		}
		if (value != null) {
			return value.isJsonPrimitive() ? value.getAsString() : value.toString();
		}
		return "?";
	}
}
