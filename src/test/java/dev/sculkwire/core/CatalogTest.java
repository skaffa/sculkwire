package dev.sculkwire.core;

import dev.sculkwire.core.event.BuiltinEvents;
import dev.sculkwire.core.event.EventRegistry;
import dev.sculkwire.core.event.EventSpec;
import dev.sculkwire.core.event.PayloadVar;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.signal.BuiltinSignals;
import dev.sculkwire.core.signal.ParamSpec;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.value.ValueType;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sanity checks on the built-in catalogs that the UI and rules rely on. */
class CatalogTest {
	@Test
	void signalsAreWellFormed() {
		SignalRegistry r = new SignalRegistry();
		BuiltinSignals.defineAll(r);
		assertTrue(r.specs().size() >= 90, "signals: " + r.specs().size());
		Set<String> ids = new HashSet<>();
		for (SignalSpec s : r.specs()) {
			assertTrue(ids.add(s.id()), "duplicate " + s.id());
			assertTrue(s.id().matches("[a-z_]+([.][a-z_]+)+"), s.id());
			assertFalse(s.description().isBlank(), s.id());
			if (s.type() == ValueType.ENUM) {
				assertFalse(s.enumOptions().isEmpty(), s.id() + " needs options");
			}
			for (ParamSpec p : s.params()) {
				assertNotNull(p.defaultValue(), s.id() + "." + p.name());
			}
		}
	}

	@Test
	void eventsAreWellFormed() {
		EventRegistry r = new EventRegistry();
		BuiltinEvents.defineAll(r);
		assertTrue(r.all().size() >= 25);
		for (EventSpec e : r.all()) {
			assertNotNull(e.filter(), e.id());
			Set<String> names = new HashSet<>();
			for (PayloadVar v : e.payload()) {
				assertTrue(names.add(v.name()), e.id() + " duplicate payload " + v.name());
			}
			Set<String> keys = new HashSet<>();
			for (FieldSpec f : e.params()) {
				assertTrue(keys.add(f.key()), e.id() + " duplicate param " + f.key());
			}
		}
		assertEquals("chat", r.get("chat.message").category());
	}
}
