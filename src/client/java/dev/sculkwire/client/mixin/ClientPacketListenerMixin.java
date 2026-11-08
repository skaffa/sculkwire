package dev.sculkwire.client.mixin;

import dev.sculkwire.client.ClientRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet hooks. Injected at RETURN: handlers first re-schedule themselves onto the game thread (throwing
 * on the network thread), so these run once, on the game thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handlePlayerCombatKill", at = @At("RETURN"))
	private void sculkwire$death(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		LocalPlayer p = Minecraft.getInstance().player;
		if (rt != null && p != null && packet.playerId() == p.getId()) {
			rt.trackers.onDeath(packet.message());
		}
	}

	@Inject(method = "handleDamageEvent", at = @At("RETURN"))
	private void sculkwire$damage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		Minecraft mc = Minecraft.getInstance();
		if (rt == null || mc.player == null || mc.level == null || packet.entityId() != mc.player.getId()) {
			return;
		}
		String attacker = "";
		Entity cause = mc.level.getEntity(packet.sourceCauseId());
		if (cause != null) {
			attacker = EntityType.getKey(cause.getType()).toString();
		}
		rt.trackers.onDamageEvent(packet.sourceType().getRegisteredName(), attacker);
	}

	@Inject(method = "handleEntityEvent", at = @At("RETURN"))
	private void sculkwire$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		Minecraft mc = Minecraft.getInstance();
		if (rt == null || mc.player == null || mc.level == null || packet.getEventId() != 35) {
			return;
		}
		if (packet.getEntity(mc.level) == mc.player) {
			rt.trackers.onTotem();
		}
	}

	@Inject(method = "handleSetExperience", at = @At("RETURN"))
	private void sculkwire$experience(ClientboundSetExperiencePacket packet, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null) {
			rt.trackers.onExperiencePacket(packet.getExperienceLevel());
		}
	}

	@Inject(method = "handleSetTime", at = @At("RETURN"))
	private void sculkwire$time(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null) {
			rt.trackers.onTimePacket(packet.gameTime());
		}
	}
}
