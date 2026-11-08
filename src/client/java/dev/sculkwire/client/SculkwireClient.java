package dev.sculkwire.client;

import dev.sculkwire.Sculkwire;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint. Wires registries, game hooks, the rule engine, UI and commands (from M1 on). */
public final class SculkwireClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Sculkwire.LOG.info("{} loaded", Sculkwire.NAME);
	}
}
