package dev.sculkwire.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Target;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Built-in rule presets from {@code assets/sculkwire/presets.json}. */
public final class Presets {
	public static final String RESOURCE = "/assets/sculkwire/presets.json";

	public record Preset(String id, String name, String description, Rule rule, List<UserVariable> variables) {
	}

	private static List<Preset> builtin;

	private Presets() {
	}

	public static synchronized List<Preset> builtin() {
		if (builtin == null) {
			try (InputStream in = Presets.class.getResourceAsStream(RESOURCE)) {
				builtin = in == null ? List.of() : load(in);
			} catch (IOException | RuntimeException e) {
				builtin = List.of();
			}
		}
		return builtin;
	}

	public static Preset find(String id) {
		for (Preset p : builtin()) {
			if (p.id().equals(id)) {
				return p;
			}
		}
		return null;
	}

	public static List<Preset> load(InputStream in) {
		JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		List<Preset> out = new ArrayList<>();
		JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("presets");
		for (JsonElement e : arr) {
			JsonObject o = e.getAsJsonObject();
			Rule r = Json.GSON.fromJson(o.get("rule"), Rule.class);
			List<UserVariable> vars = new ArrayList<>();
			if (o.has("variables")) {
				for (JsonElement v : o.getAsJsonArray("variables")) {
					vars.add(Json.GSON.fromJson(v, UserVariable.class));
				}
			}
			out.add(new Preset(Json.str(o, "id", ""), Json.str(o, "name", ""), Json.str(o, "description", ""), r, List.copyOf(vars)));
		}
		return List.copyOf(out);
	}

	/** Adds a copy of the preset to {@code cfg}, sending to the given notifiers. Returns the new rule. */
	public static Rule instantiate(Preset p, SculkwireConfig cfg, Collection<String> notifierIds) {
		Rule r = p.rule().copy();
		r.id = cfg.uniqueId(p.id());
		if (r.name == null || r.name.isBlank()) {
			r.name = p.name();
		}
		r.enabled = true;
		r.targets = new ArrayList<>();
		for (String n : notifierIds) {
			r.targets.add(Target.of(n));
		}
		cfg.rules.add(r);
		for (UserVariable v : p.variables()) {
			if (cfg.variable(v.name) == null) {
				cfg.variables.add(Json.copy(v, UserVariable.class));
			}
		}
		cfg.sanitize();
		return r;
	}
}
