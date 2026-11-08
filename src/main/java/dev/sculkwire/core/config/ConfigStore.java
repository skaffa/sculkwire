package dev.sculkwire.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and saves {@code config/sculkwire/config.json}. Writes are atomic; older schemas are migrated
 * (with a backup first); a broken file is kept aside and never overwritten silently.
 */
public final class ConfigStore {
	public static final String FILE = "config.json";

	public record Loaded(SculkwireConfig config, List<String> warnings, boolean fresh) {
	}

	private final Path dir;

	public ConfigStore(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	public Path file() {
		return dir.resolve(FILE);
	}

	public Loaded load() {
		List<String> warnings = new ArrayList<>();
		Path f = file();
		if (!Files.exists(f)) {
			return new Loaded(new SculkwireConfig(), warnings, true);
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				throw new IllegalStateException("not a JSON object");
			}
			JsonObject o = root.getAsJsonObject();
			int version = o.has("schema_version") ? o.get("schema_version").getAsInt() : 1;
			if (version < SculkwireConfig.SCHEMA_VERSION) {
				Files.copy(f, dir.resolve(FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING);
				o = Migrations.migrate(o, version, warnings);
			} else if (version > SculkwireConfig.SCHEMA_VERSION) {
				warnings.add("config.json comes from a newer Sculkwire (schema " + version + "); unknown settings are ignored.");
			}
			SculkwireConfig cfg = Json.GSON.fromJson(o, SculkwireConfig.class);
			if (cfg == null) {
				cfg = new SculkwireConfig();
			}
			cfg.sanitize();
			cfg.schemaVersion = SculkwireConfig.SCHEMA_VERSION;
			return new Loaded(cfg, warnings, false);
		} catch (IOException | RuntimeException e) {
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Path broken = dir.resolve(FILE + ".broken-" + stamp);
			try {
				Files.move(f, broken, StandardCopyOption.REPLACE_EXISTING);
				warnings.add("config.json could not be read (" + e.getMessage() + "). It was renamed to "
						+ broken.getFileName() + " and default settings are in use.");
			} catch (IOException moveFailed) {
				warnings.add("config.json could not be read (" + e.getMessage() + ") and could not be moved aside.");
			}
			return new Loaded(new SculkwireConfig(), warnings, true);
		}
	}

	public void save(SculkwireConfig cfg) throws IOException {
		cfg.schemaVersion = SculkwireConfig.SCHEMA_VERSION;
		writeAtomic(file(), Json.GSON.toJson(cfg));
	}

	public static void writeAtomic(Path target, String content) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
