package dev.sculkwire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants.
 *
 * <p>Everything under {@code src/main} (the core: engine, templating, notifiers, config) must not
 * import Minecraft classes, so it can be tested with plain JUnit. Game hooks live in {@code src/client}.
 */
public final class Sculkwire {
	public static final String MOD_ID = "sculkwire";
	public static final String NAME = "Sculkwire";
	public static final Logger LOG = LoggerFactory.getLogger(NAME);

	private Sculkwire() {
	}
}
