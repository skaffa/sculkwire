package dev.sculkwire.client.mixin;

import dev.sculkwire.client.ClientRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sounds the client plays, for the sound.played event (skipped unless a rule listens for it). */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"))
	private void sculkwire$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt == null || sound == null || !Minecraft.getInstance().isSameThread()) {
			return;
		}
		try {
			rt.trackers.onSound(sound.getIdentifier(), sound.getX(), sound.getY(), sound.getZ());
		} catch (RuntimeException ignored) {
			// some sound instances are not fully set up yet
		}
	}
}
