package dev.sculkwire.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Root of config/sculkwire/config.json. */
public final class SculkwireConfig {
	public static final int SCHEMA_VERSION = 1;

	public int schemaVersion = SCHEMA_VERSION;
	public Settings settings = new Settings();
	public List<NotifierConfig> notifiers = new ArrayList<>();
	public List<Rule> rules = new ArrayList<>();
	public List<UserVariable> variables = new ArrayList<>();

	public NotifierConfig notifier(String id) {
		for (NotifierConfig n : notifiers) {
			if (n.id.equals(id)) {
				return n;
			}
		}
		return null;
	}

	public Rule rule(String id) {
		for (Rule r : rules) {
			if (r.id.equals(id)) {
				return r;
			}
		}
		return null;
	}

	public UserVariable variable(String name) {
		for (UserVariable v : variables) {
			if (v.name.equals(name)) {
				return v;
			}
		}
		return null;
	}

	/** A new id based on {@code base} that no rule or notifier uses yet. */
	public String uniqueId(String base) {
		String slug = base == null ? "" : base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
		if (slug.isEmpty()) {
			slug = "item";
		}
		String id = slug;
		int i = 2;
		while (rule(id) != null || notifier(id) != null) {
			id = slug + "-" + i++;
		}
		return id;
	}

	/** Fills nulls left by hand-edited or older JSON so the rest of the code can rely on them. */
	public SculkwireConfig sanitize() {
		if (settings == null) {
			settings = new Settings();
		}
		if (settings.quietHours == null) {
			settings.quietHours = new Settings.QuietHours();
		}
		if (notifiers == null) {
			notifiers = new ArrayList<>();
		}
		if (rules == null) {
			rules = new ArrayList<>();
		}
		if (variables == null) {
			variables = new ArrayList<>();
		}
		notifiers.removeIf(n -> n == null);
		rules.removeIf(r -> r == null);
		variables.removeIf(v -> v == null);
		for (NotifierConfig n : notifiers) {
			if (n.settings == null) {
				n.settings = new com.google.gson.JsonObject();
			}
			if (n.id == null) {
				n.id = "";
			}
		}
		for (Rule r : rules) {
			if (r.id == null || r.id.isBlank()) {
				r.id = uniqueId(r.name);
			}
			if (r.name == null) {
				r.name = r.id;
			}
			if (r.trigger == null) {
				r.trigger = new Trigger();
			}
			if (r.scope == null) {
				r.scope = new Scope();
			}
			if (r.throttle == null) {
				r.throttle = new Throttle();
			}
			if (r.message == null) {
				r.message = MessageTemplate.of("", "");
			}
			if (r.targets == null) {
				r.targets = new ArrayList<>();
			}
			r.targets.removeIf(t -> t == null || t.notifier == null);
			if (r.branches != null) {
				r.branches.removeIf(b -> b == null);
				for (Branch b : r.branches) {
					if (b.sends == null) {
						b.sends = new ArrayList<>();
					}
					b.sends.removeIf(s -> s == null);
					for (Send s : b.sends) {
						if (s.message == null) {
							s.message = MessageTemplate.of("", "");
						}
						if (s.notifiers == null) {
							s.notifiers = new ArrayList<>();
						}
					}
				}
			}
		}
		return this;
	}
}
