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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Sharing rules as JSON. Exports never contain notifier settings or secrets, only notifier ids, which
 * the importer maps to their own notifiers. Imported rules arrive disabled.
 */
public final class ImportExport {
	public static final String MARKER = "sculkwire_export";

	public record Imported(List<Rule> rules, List<UserVariable> variables, List<String> notifierRefs, List<String> problems) {
	}

	private ImportExport() {
	}

	public static String export(Collection<Rule> rules, Collection<UserVariable> variables) {
		JsonObject root = new JsonObject();
		root.addProperty(MARKER, 1);
		JsonArray rs = new JsonArray();
		TreeSet<String> refs = new TreeSet<>();
		for (Rule r : rules) {
			rs.add(Json.GSON.toJsonTree(r));
			for (Target t : r.targets) {
				refs.add(t.notifier);
			}
		}
		root.add("rules", rs);
		root.add("variables", Json.GSON.toJsonTree(variables == null ? List.of() : variables));
		JsonArray ra = new JsonArray();
		refs.forEach(ra::add);
		root.add("notifier_refs", ra);
		return Json.GSON.toJson(root);
	}

	public static Imported parse(String text) {
		List<Rule> rules = new ArrayList<>();
		List<UserVariable> vars = new ArrayList<>();
		List<String> refs = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		JsonElement root;
		try {
			root = JsonParser.parseString(text == null ? "" : text.trim());
		} catch (RuntimeException e) {
			problems.add("Not valid JSON: " + e.getMessage());
			return new Imported(rules, vars, refs, problems);
		}
		if (root == null || !root.isJsonObject()) {
			problems.add("Not a Sculkwire export");
			return new Imported(rules, vars, refs, problems);
		}
		JsonObject o = root.getAsJsonObject();
		if (!o.has(MARKER)) {
			if (o.has("trigger")) {
				JsonObject wrap = new JsonObject();
				JsonArray a = new JsonArray();
				a.add(o);
				wrap.add("rules", a);
				o = wrap;
			} else {
				problems.add("Not a Sculkwire export");
				return new Imported(rules, vars, refs, problems);
			}
		}
		if (o.has("rules") && o.get("rules").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("rules")) {
				try {
					Rule r = Json.GSON.fromJson(e, Rule.class);
					if (r != null) {
						r.enabled = false;
						rules.add(r);
					}
				} catch (RuntimeException ex) {
					problems.add("Skipped a rule that could not be read: " + ex.getMessage());
				}
			}
		}
		if (o.has("variables") && o.get("variables").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("variables")) {
				try {
					UserVariable v = Json.GSON.fromJson(e, UserVariable.class);
					if (v != null && v.name != null && !v.name.isBlank()) {
						vars.add(v);
					}
				} catch (RuntimeException ex) {
					problems.add("Skipped a variable that could not be read");
				}
			}
		}
		TreeSet<String> refSet = new TreeSet<>();
		for (Rule r : rules) {
			if (r.targets != null) {
				r.targets.forEach(t -> refSet.add(t.notifier));
			}
		}
		refs.addAll(refSet);
		if (rules.isEmpty() && problems.isEmpty()) {
			problems.add("The export contains no rules");
		}
		return new Imported(rules, vars, refs, problems);
	}

	/**
	 * Adds the imported rules to {@code cfg} (with fresh unique ids, disabled), remapping notifier ids via
	 * {@code notifierMap} (missing or null mapping drops that target). Variables that do not exist yet are
	 * added. Returns the added rules.
	 */
	public static List<Rule> apply(SculkwireConfig cfg, Imported imp, Map<String, String> notifierMap) {
		List<Rule> added = new ArrayList<>();
		for (Rule src : imp.rules()) {
			Rule r = src.copy();
			r.id = cfg.uniqueId(r.name == null || r.name.isBlank() ? r.id : r.name);
			r.enabled = false;
			List<Target> targets = new ArrayList<>();
			if (r.targets != null) {
				for (Target t : r.targets) {
					String mapped = notifierMap == null ? null : notifierMap.get(t.notifier);
					if (mapped != null && cfg.notifier(mapped) != null) {
						t.notifier = mapped;
						targets.add(t);
					}
				}
			}
			r.targets = targets;
			cfg.rules.add(r);
			added.add(r);
		}
		for (UserVariable v : imp.variables()) {
			if (cfg.variable(v.name) == null) {
				cfg.variables.add(v);
			}
		}
		cfg.sanitize();
		return added;
	}
}
