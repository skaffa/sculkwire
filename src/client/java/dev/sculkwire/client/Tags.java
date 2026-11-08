package dev.sculkwire.client;

import dev.sculkwire.core.value.TagResolver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Resolves {@code #tag} membership across the item, block, entity, effect, biome and damage type registries. */
public final class Tags implements TagResolver {
	@Override
	public boolean inTag(String id, String tag) {
		Identifier idl = Identifier.tryParse(id);
		Identifier tl = Identifier.tryParse(tag.startsWith("#") ? tag.substring(1) : tag);
		if (idl == null || tl == null) {
			return false;
		}
		if (check(BuiltInRegistries.ITEM, idl, tl) || check(BuiltInRegistries.BLOCK, idl, tl)
				|| check(BuiltInRegistries.ENTITY_TYPE, idl, tl) || check(BuiltInRegistries.MOB_EFFECT, idl, tl)) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			var access = mc.level.registryAccess();
			Optional<Registry<net.minecraft.world.level.biome.Biome>> biomes = access.lookup(Registries.BIOME);
			if (biomes.isPresent() && check(biomes.get(), idl, tl)) {
				return true;
			}
			Optional<Registry<net.minecraft.world.damagesource.DamageType>> dmg = access.lookup(Registries.DAMAGE_TYPE);
			return dmg.isPresent() && check(dmg.get(), idl, tl);
		}
		return false;
	}

	private static <T> boolean check(Registry<T> reg, Identifier id, Identifier tag) {
		Optional<Holder.Reference<T>> h = reg.get(id);
		return h.isPresent() && h.get().tags().anyMatch(t -> t.location().equals(tag));
	}
}
