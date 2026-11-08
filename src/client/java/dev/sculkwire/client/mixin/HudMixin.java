package dev.sculkwire.client.mixin;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.client.notify.InGameNotifierType;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Titles, subtitles and the action bar, for hud.* signals and events. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "setTitle", at = @At("HEAD"))
	private void sculkwire$title(Component title, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null && !InGameNotifierType.showing) {
			rt.trackers.onTitle(title);
		}
	}

	@Inject(method = "setSubtitle", at = @At("HEAD"))
	private void sculkwire$subtitle(Component subtitle, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null && !InGameNotifierType.showing) {
			rt.trackers.onSubtitle(subtitle);
		}
	}

	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void sculkwire$actionbar(Component message, boolean animate, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null && !InGameNotifierType.showing) {
			rt.trackers.onActionbar(message);
		}
	}

	@Inject(method = "clearTitles", at = @At("HEAD"))
	private void sculkwire$clear(CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null) {
			rt.trackers.onClearTitles();
		}
	}
}
