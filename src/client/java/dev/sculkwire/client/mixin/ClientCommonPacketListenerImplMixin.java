package dev.sculkwire.client.mixin;

import dev.sculkwire.client.ClientRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the disconnect reason, before Fabric's DISCONNECT event resets the session. */
@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 900)
public abstract class ClientCommonPacketListenerImplMixin {
	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void sculkwire$disconnect(DisconnectionDetails details, CallbackInfo ci) {
		ClientRuntime rt = ClientRuntime.get();
		if (rt == null) {
			return;
		}
		Component reason = details.reason();
		boolean voluntary = reason != null && reason.equals(ClientLevel.DEFAULT_QUIT_MESSAGE);
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			rt.trackers.onDisconnectReason(reason, voluntary);
		} else {
			mc.execute(() -> rt.trackers.onDisconnectReason(reason, voluntary));
		}
	}
}
