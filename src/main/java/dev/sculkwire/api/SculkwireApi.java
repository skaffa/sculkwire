package dev.sculkwire.api;

import dev.sculkwire.core.value.Value;

import java.util.Map;
import java.util.function.BiConsumer;

/** Static access for other mods. Experimental until Sculkwire 1.0. */
public final class SculkwireApi {
	private static volatile BiConsumer<String, Map<String, Value>> poster;

	private SculkwireApi() {
	}

	/** Set by the Sculkwire client at startup. */
	public static void bind(BiConsumer<String, Map<String, Value>> eventPoster) {
		poster = eventPoster;
	}

	public static boolean isAvailable() {
		return poster != null;
	}

	/** Posts an event (a registered id, or {@code custom} with a {@code name}). Call on the game thread. */
	public static void emit(String eventId, Map<String, Value> payload) {
		BiConsumer<String, Map<String, Value>> p = poster;
		if (p != null) {
			p.accept(eventId, payload == null ? Map.of() : payload);
		}
	}
}
