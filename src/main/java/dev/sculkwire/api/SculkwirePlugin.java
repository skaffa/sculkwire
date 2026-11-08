package dev.sculkwire.api;

import dev.sculkwire.core.event.EventRegistry;
import dev.sculkwire.core.notify.NotifierRegistry;
import dev.sculkwire.core.signal.SignalRegistry;

/**
 * Entrypoint for other mods (key {@code "sculkwire"} in fabric.mod.json). Ids you register must be
 * namespaced, e.g. {@code mymod:mana}. Experimental until Sculkwire 1.0.
 */
public interface SculkwirePlugin {
	default void registerSignals(SignalRegistry signals) {
	}

	default void registerEvents(EventRegistry events) {
	}

	default void registerNotifierTypes(NotifierRegistry notifiers) {
	}
}
