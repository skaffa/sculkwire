package dev.sculkwire.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.sculkwire.client.ui.SculkwireScreen;

/** Adds a "Configure" button for Sculkwire in Mod Menu. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SculkwireScreen::new;
	}

	/** Dev aid ({@code -Pdevshots=modmenu}): screenshots of the mod list and of the screen the button opens. */
	public static void registerDevShots() {
		dev.sculkwire.client.ui.DevScreenshots.add("modmenu-list", () -> ModMenuApi.createModsScreen(null));
		dev.sculkwire.client.ui.DevScreenshots.add("modmenu-config", () -> new ModMenuIntegration().getModConfigScreenFactory().create(null));
	}
}
