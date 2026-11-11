package dev.sculkwire.client.ui;

import dev.sculkwire.Sculkwire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Development aid, off by default: with {@code -Dsculkwire.devshots=<mode>} it runs a list of steps after
 * joining a world (or at the title screen for "modmenu"), each followed by a screenshot in
 * screenshots/sculkwire-*.png. A step opens a screen or performs an action (e.g. simulated mouse input).
 */
public final class DevScreenshots {
	private static final String MODE = System.getProperty("sculkwire.devshots", "");
	private static final boolean ENABLED = !MODE.isEmpty() && !MODE.equals("false");
	private static final List<Runnable> STEPS = new ArrayList<>();
	private static final List<String> NAMES = new ArrayList<>();
	private static int step = -1;
	private static int wait;

	private DevScreenshots() {
	}

	public static String mode() {
		return MODE;
	}

	/** "modmenu" mode starts at the title screen instead of after joining a world. */
	public static boolean titleMode() {
		return MODE.equals("modmenu");
	}

	public static void onStarted() {
		if (ENABLED && titleMode() && step < 0) {
			step = 0;
			wait = 60;
		}
	}

	public static void add(String name, Supplier<Screen> screen) {
		NAMES.add(name);
		STEPS.add(() -> {
			Minecraft mc = Minecraft.getInstance();
			mc.gui.toastManager().clear();
			try {
				// park the cursor in the corner so no tooltip shows in the screenshot
				org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(mc.getWindow().handle(), 1f, 1f);
				mc.mouseHandler.resyncMousePosition();
			} catch (RuntimeException | LinkageError e) {
				Sculkwire.LOG.debug("could not move the cursor", e);
			}
			mc.gui.setScreen(screen.get());
		});
	}

	public static void addAction(String name, Runnable action) {
		NAMES.add(name);
		STEPS.add(action);
	}

	public static void onJoin() {
		if (ENABLED && !titleMode() && step < 0) {
			step = 0;
			wait = 100;
		}
	}

	public static void tick(Minecraft mc) {
		if (!ENABLED || step < 0 || step > STEPS.size()) {
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
		if (step < STEPS.size()) {
			try {
				STEPS.get(step).run();
			} catch (RuntimeException e) {
				Sculkwire.LOG.warn("dev step {} failed", NAMES.get(step), e);
			}
		} else {
			mc.gui.setScreen(null);
		}
		step++;
		wait = 30;
	}
}
