package dev.sculkwire.core.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Wall clock, injectable so the engine can be tested with fake time. */
public interface Clock {
	long millis();

	default ZoneId zone() {
		return ZoneId.systemDefault();
	}

	default ZonedDateTime now() {
		return Instant.ofEpochMilli(millis()).atZone(zone());
	}

	static Clock system() {
		return System::currentTimeMillis;
	}
}
