package dev.sculkwire.core.notify.types;

import com.google.gson.JsonObject;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.notify.HttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.Notifier;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.notify.SecretSource;
import dev.sculkwire.core.notify.SendResult;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Appends notifications to a file in config/sculkwire/logs/ (text or JSON lines). Nothing leaves the computer. */
public final class FileLogType implements NotifierType {
	public static final String ID = "file";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("file", FieldKind.TEXT, "File name").withDefault("notifications.log")
					.withHint("Inside config/sculkwire/logs/"),
			FieldSpec.of("format", FieldKind.ENUM, "Format").withOptions("text", "jsonl").withDefault("text"));

	private final Path dir;

	public FileLogType(Path dir) {
		this.dir = dir;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Log file";
	}

	@Override
	public String description() {
		return "Appends each notification to a file on your computer (for scripts or a record).";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 0;
	}

	/** Resolves the file inside the logs folder, or null when the name tries to leave it. */
	Path resolve(String name) {
		String n = name == null || name.isBlank() ? "notifications.log" : name.trim();
		Path p = dir.resolve(n).normalize();
		return p.startsWith(dir.normalize()) ? p : null;
	}

	@Override
	public List<String> validate(NotifierConfig cfg, SecretSource secrets) {
		List<String> out = new ArrayList<>(NotifierType.super.validate(cfg, secrets));
		if (resolve(field("file").string(cfg.settings)) == null) {
			out.add("File name must stay inside the logs folder");
		}
		return out;
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		Path file = resolve(field("file").string(cfg.settings));
		boolean jsonl = field("format").string(cfg.settings).equals("jsonl");
		return n -> {
			if (file == null) {
				return CompletableFuture.completedFuture(SendResult.fail(0, "File name must stay inside the logs folder"));
			}
			String line;
			if (jsonl) {
				JsonObject o = new JsonObject();
				o.addProperty("time", Instant.ofEpochMilli(n.createdAt()).toString());
				o.addProperty("rule", n.ruleName());
				o.addProperty("priority", n.priority().id());
				o.addProperty("title", n.title(OutputContext.PLAIN));
				o.addProperty("body", n.body(OutputContext.PLAIN));
				o.addProperty("test", n.test());
				line = Json.COMPACT.toJson(o);
			} else {
				line = TIME.format(Instant.ofEpochMilli(n.createdAt())) + " [" + n.priority().id() + "] "
						+ (n.test() ? "[test] " : "") + n.title(OutputContext.PLAIN) + " | "
						+ n.body(OutputContext.PLAIN).replace("\n", " / ");
			}
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				return CompletableFuture.completedFuture(SendResult.ok(0));
			} catch (IOException e) {
				return CompletableFuture.completedFuture(SendResult.fail(0, "Could not write " + file.getFileName() + ": " + e.getMessage()));
			}
		};
	}
}
