package dev.sculkwire.client.ui;

import dev.sculkwire.Sculkwire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Development aid, off by default: with {@code -Dsculkwire.devshots=true} it opens each editor screen
 * after joining a world and saves a screenshot of it (screenshots/sculkwire-*.png). Used to check the
 * UI without clicking through it.
 */
public final class DevScreenshots {
	private static final boolean ENABLED = Boolean.getBoolean("sculkwire.devshots");
	private static final List<Supplier<Screen>> SCREENS = new ArrayList<>();
	private static final List<String> NAMES = new ArrayList<>();
	private static int step = -1;
	private static int wait;

	private DevScreenshots() {
	}

	public static void add(String name, Supplier<Screen> screen) {
		NAMES.add(name);
		SCREENS.add(screen);
	}

	public static void onJoin() {
		if (ENABLED && step < 0) {
			step = 0;
			wait = 100;
		}
	}

	public static void tick(Minecraft mc) {
		if (!ENABLED || step < 0 || step > SCREENS.size()) {
			return;
		}
		if (--wait > 0) {
			return;
		}
		if (step > 0) {
			String name = "sculkwire-" + NAMES.get(step - 1);
			Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
					msg -> Sculkwire.LOG.info("dev screenshot {}: {}", name, msg.getString()));
		}
		if (step < SCREENS.size()) {
			try {
				mc.gui.setScreen(SCREENS.get(step).get());
			} catch (RuntimeException e) {
				Sculkwire.LOG.warn("dev screenshot {} failed", NAMES.get(step), e);
			}
		} else {
			mc.gui.setScreen(null);
		}
		step++;
		wait = 30;
	}
}
