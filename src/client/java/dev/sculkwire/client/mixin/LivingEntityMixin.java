package dev.sculkwire.client.mixin;

import dev.sculkwire.client.ClientRuntime;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item break effects (the client is told through an entity event) for the player.item_broken event. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "onEquippedItemBroken", at = @At("HEAD"))
	private void sculkwire$broken(ItemStack stack, EquipmentSlot slot, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		ClientRuntime rt = ClientRuntime.get();
		if (rt != null && (Object) this == mc.player && mc.isSameThread()) {
			rt.trackers.onItemBroken(stack, slot);
		}
	}
}
