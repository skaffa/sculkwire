package dev.sculkwire.core.notify;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;

import java.util.ArrayList;
import java.util.List;

/** A kind of notifier (ntfy, Discord, webhook, ...). Its fields drive the settings form. */
public interface NotifierType {
	String id();

	String displayName();

	String description();

	List<FieldSpec> fields();

	/** Minimum time between two sends to one instance (simple rate limit). */
	long minIntervalMillis();

	Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http);

	/** False hides the type (e.g. a desktop notifier on an unsupported platform). */
	default boolean available() {
		return true;
	}

	/** Problems that make the notifier unusable (missing required fields, insecure URLs). */
	default List<String> validate(NotifierConfig cfg, SecretSource secrets) {
		List<String> out = new ArrayList<>();
		boolean insecure = cfg.settings != null && cfg.settings.has("allow_insecure") && cfg.settings.get("allow_insecure").getAsBoolean();
		for (FieldSpec f : fields()) {
			String v = value(f, cfg, secrets);
			if (f.required() && v.isBlank()) {
				out.add(f.label() + " is required");
			} else if (f.kind() == FieldKind.URL && !v.isBlank()) {
				String err = UrlPolicy.check(v, insecure);
				if (err != null) {
					out.add(f.label() + " " + err);
				}
			}
		}
		return out;
	}

	static String value(FieldSpec f, NotifierConfig cfg, SecretSource secrets) {
		return f.secret() ? secrets.get(cfg.id, f.key()) : f.string(cfg.settings);
	}

	default FieldSpec field(String key) {
		for (FieldSpec f : fields()) {
			if (f.key().equals(key)) {
				return f;
			}
		}
		return null;
	}
}
