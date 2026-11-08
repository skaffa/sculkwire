package dev.sculkwire.core.engine;

import com.google.gson.JsonElement;

import dev.sculkwire.core.signal.SignalReader;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.template.VariableResolver;
import dev.sculkwire.core.value.TagResolver;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a condition or template can look up during one evaluation: event payload, rule built-ins,
 * signals (memoized per tick by the engine), user variables and tags.
 *
 * <p>Name resolution order: payload, built-ins, signals, user variables ({@code var.x} or bare {@code x}).
 */
public final class EvalContext implements VariableResolver {
	public static final Set<String> BUILTINS = Set.of("rule.name", "rule.id", "now");

	private final SignalReader signals;
	private final Map<String, Value> payload;
	private final Map<String, Value> vars;
	private final Map<String, Value> builtins;
	private final TagResolver tags;
	private final Map<Object, Value> memory;

	public EvalContext(SignalReader signals, Map<String, Value> payload, Map<String, Value> vars,
			Map<String, Value> builtins, TagResolver tags, Map<Object, Value> memory) {
		this.signals = signals;
		this.payload = payload;
		this.vars = vars == null ? Map.of() : vars;
		this.builtins = builtins == null ? Map.of() : builtins;
		this.tags = tags == null ? TagResolver.NONE : tags;
		this.memory = memory == null ? new HashMap<>() : memory;
	}

	public Value signal(String id, Map<String, ?> params) {
		return signals.read(id, params == null ? Map.of() : params);
	}

	public SignalReader signals() {
		return signals;
	}

	public Value payload(String name) {
		Value v = payload.get(name);
		return v == null ? Value.NONE : v;
	}

	public Map<String, Value> payloadMap() {
		return payload;
	}

	public void putPayload(String name, Value v) {
		payload.put(name, v == null ? Value.NONE : v);
	}

	public Value var(String name) {
		String n = name.startsWith("var.") ? name.substring(4) : name;
		Value v = vars.get(n);
		return v == null ? Value.NONE : v;
	}

	public TagResolver tags() {
		return tags;
	}

	/** Per-rule memory (e.g. previous values for the 'changed' operator). */
	public Map<Object, Value> memory() {
		return memory;
	}

	@Override
	public Value resolve(String name, Map<String, String> params) {
		if (params == null || params.isEmpty()) {
			Value p = payload.get(name);
			if (p != null) {
				return p;
			}
			Value b = builtins.get(name);
			if (b != null) {
				return b;
			}
		}
		if (signals.spec(name) != null) {
			return signals.read(name, params == null ? Map.of() : params);
		}
		if (name.startsWith("var.")) {
			return var(name);
		}
		Value v = vars.get(name);
		return v == null ? Value.NONE : v;
	}

	public String render(String template, OutputContext ctx) {
		return Template.parse(template).render(this, ctx);
	}

	/**
	 * Reads a list parameter: a JSON array or comma-separated string; items may be templates, and an item
	 * that is exactly one {list variable} expands to all of its entries.
	 */
	public List<String> resolveList(JsonElement e) {
		List<String> out = new ArrayList<>();
		if (e == null || e.isJsonNull()) {
			return out;
		}
		if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				if (x.isJsonPrimitive()) {
					expand(x.getAsString(), out);
				}
			}
			return out;
		}
		String s = e.isJsonPrimitive() ? e.getAsString() : e.toString();
		Template whole = Template.parse(s.trim());
		if (whole.parts().size() == 1 && whole.parts().get(0) instanceof Template.Expr) {
			expand(s, out);
			return out;
		}
		for (String part : Value.splitList(s)) {
			expand(part, out);
		}
		return out;
	}

	private void expand(String item, List<String> out) {
		String t = item.trim();
		if (t.isEmpty()) {
			return;
		}
		Template tpl = Template.parse(t);
		if (tpl.parts().size() == 1 && tpl.parts().get(0) instanceof Template.Expr expr && expr.filters().isEmpty()) {
			Value v = resolve(expr.name(), expr.params());
			if (v.type() == ValueType.LIST) {
				out.addAll(v.asList());
			} else if (!v.asString().isEmpty()) {
				out.add(v.asString());
			}
			return;
		}
		String r = tpl.render(this, OutputContext.PLAIN).trim();
		if (!r.isEmpty()) {
			out.add(r);
		}
	}
}
