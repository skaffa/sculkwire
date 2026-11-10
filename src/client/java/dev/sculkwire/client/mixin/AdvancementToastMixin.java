package dev.sculkwire.client.mixin;

import dev.sculkwire.client.ClientRuntime;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.components.toasts.AdvancementToast;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An advancement toast means the player just made an advancement. */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void sculkwire$made(AdvancementHolder holder, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt == null) {
			return;
		}
		String title = holder.value().display().map(d -> d.title().getString()).orElse(holder.id().toString());
		String type = holder.value().display().map(d -> d.type().getSerializedName()).orElse("task");
		rt.trackers.onAdvancement(title, holder.id().toString(), type);
	}
}
