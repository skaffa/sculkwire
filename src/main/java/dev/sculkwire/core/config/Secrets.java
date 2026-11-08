package dev.sculkwire.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sculkwire.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret notifier settings (webhook URLs, tokens) in {@code secrets.json}, separate from the shareable
 * config. Values may reference environment variables as {@code ${env:NAME}}. Stored as plain text.
 */
public final class Secrets implements dev.sculkwire.core.notify.SecretSource {
	private static final Pattern ENV = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

	private final Path file;
	private JsonObject data = new JsonObject();
	private Function<String, String> env = System::getenv;

	public Secrets(Path file) {
		this.file = file;
	}

	public static Secrets inMemory() {
		return new Secrets(null);
	}

	/** For tests. */
	public Secrets withEnv(Function<String, String> env) {
		this.env = env;
		return this;
	}

	/** Loads the file; returns a warning message, or null when fine. */
	public String load() {
		if (file == null || !Files.exists(file)) {
			return null;
		}
		try {
			JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			data = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
			return null;
		} catch (IOException | RuntimeException e) {
			data = new JsonObject();
			return "secrets.json could not be read (" + e.getMessage() + "); notifiers that need a URL or token will fail.";
		}
	}

	public void save() throws IOException {
		if (file == null) {
			return;
		}
		ConfigStore.writeAtomic(file, Json.GSON.toJson(data));
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// not a POSIX file system (Windows)
		}
	}

	/** The stored value with ${env:NAME} references expanded. Empty string when missing. */
	@Override
	public String get(String notifierId, String key) {
		String raw = raw(notifierId, key);
		if (raw.isEmpty() || raw.indexOf("${env:") < 0) {
			return raw;
		}
		Matcher m = ENV.matcher(raw);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = env.apply(m.group(1));
			m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	public String raw(String notifierId, String key) {
		JsonObject o = data.has(notifierId) && data.get(notifierId).isJsonObject() ? data.getAsJsonObject(notifierId) : null;
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
			return "";
		}
		return o.get(key).getAsString();
	}

	public boolean has(String notifierId, String key) {
		return !raw(notifierId, key).isEmpty();
	}

	public void set(String notifierId, String key, String value) {
		JsonObject o = data.has(notifierId) && data.get(notifierId).isJsonObject() ? data.getAsJsonObject(notifierId) : new JsonObject();
		if (value == null || value.isEmpty()) {
			o.remove(key);
		} else {
			o.addProperty(key, value);
		}
		if (o.size() == 0) {
			data.remove(notifierId);
		} else {
			data.add(notifierId, o);
		}
	}

	public void removeNotifier(String notifierId) {
		data.remove(notifierId);
	}

	public void renameNotifier(String from, String to) {
		JsonElement e = data.remove(from);
		if (e != null) {
			data.add(to, e);
		}
	}

	public void copyNotifier(String from, String to) {
		JsonElement e = data.get(from);
		if (e != null) {
			data.add(to, e.deepCopy());
		}
	}

	/** All secret values (expanded), for redacting logs. */
	public Set<String> allValues() {
		Set<String> out = new HashSet<>();
		for (Map.Entry<String, JsonElement> n : data.entrySet()) {
			if (n.getValue().isJsonObject()) {
				for (String key : n.getValue().getAsJsonObject().keySet()) {
					String v = get(n.getKey(), key);
					if (!v.isEmpty()) {
						out.add(v);
					}
				}
			}
		}
		return out;
	}
}
