package dev.sculkwire.core.dispatch;

import com.google.gson.JsonParser;

import dev.sculkwire.core.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The last deliveries (in memory, newest first), also appended to history.jsonl (capped at ~1 MB). */
public final class History {
	public static final long MAX_FILE_BYTES = 1_000_000;

	public enum Status {
		SENT,
		FAILED,
		DROPPED
	}

	public record Entry(long time, String ruleId, String ruleName, String notifierId, String notifierName, String title,
			String body, Status status, int httpStatus, String error, int attempts, long latencyMs, boolean test) {
	}

	private final Path file;
	private final ArrayDeque<Entry> entries = new ArrayDeque<>();
	private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
	private volatile int max;

	public History(Path file, int max) {
		this.file = file;
		this.max = Math.max(10, max);
	}

	public void setMax(int max) {
		this.max = Math.max(10, max);
		synchronized (entries) {
			while (entries.size() > this.max) {
				entries.removeLast();
			}
		}
	}

	public void addListener(Consumer<Entry> l) {
		listeners.add(l);
	}

	public void add(Entry e) {
		synchronized (entries) {
			entries.addFirst(e);
			while (entries.size() > max) {
				entries.removeLast();
			}
		}
		append(e);
		for (Consumer<Entry> l : listeners) {
			try {
				l.accept(e);
			} catch (RuntimeException ignored) {
				// a broken listener must not stop delivery
			}
		}
	}

	public List<Entry> entries() {
		synchronized (entries) {
			return Collections.unmodifiableList(new ArrayList<>(entries));
		}
	}

	public void clear() {
		synchronized (entries) {
			entries.clear();
		}
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	/** Reads the newest entries back from the file. */
	public void load() {
		if (file == null || !Files.exists(file)) {
			return;
		}
		List<Entry> read = new ArrayList<>();
		try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				try {
					Entry e = Json.COMPACT.fromJson(JsonParser.parseString(line), Entry.class);
					if (e != null) {
						read.add(e);
					}
				} catch (RuntimeException ignored) {
					// skip a damaged line
				}
			}
		} catch (IOException ignored) {
			return;
		}
		synchronized (entries) {
			entries.clear();
			for (int i = read.size() - 1; i >= 0 && entries.size() < max; i--) {
				entries.addLast(read.get(i));
			}
		}
	}

	private void append(Entry e) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Files.writeString(file, Json.COMPACT.toJson(e) + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			if (Files.size(file) > MAX_FILE_BYTES) {
				rewrite();
			}
		} catch (IOException ignored) {
			// history is best effort
		}
	}

	private void rewrite() throws IOException {
		List<Entry> copy;
		synchronized (entries) {
			copy = new ArrayList<>(entries);
		}
		StringBuilder sb = new StringBuilder();
		for (int i = copy.size() - 1; i >= 0; i--) {
			sb.append(Json.COMPACT.toJson(copy.get(i))).append('\n');
		}
		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
	}
}
