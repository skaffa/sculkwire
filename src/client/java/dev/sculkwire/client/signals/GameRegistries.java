package dev.sculkwire.client.signals;

import dev.sculkwire.core.value.Value;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.StatType;
import net.minecraft.stats.StatsCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Statistics and registry id lists (for pickers) on the client. */
public final class GameRegistries {
	private GameRegistries() {
	}

	public static StatType<?> statType(String category) {
		Identifier id = Identifier.tryParse(category.contains(":") ? category : "minecraft:" + category);
		return id == null ? null : BuiltInRegistries.STAT_TYPE.getValue(id);
	}

	/** The value of a statistic as the client last received it, or NONE for an unknown statistic. */
	public static Value stat(LocalPlayer player, String category, String statId) {
		StatType<?> type = statType(category);
		Identifier id = Identifier.tryParse(Value.normalizeId(statId));
		if (type == null || id == null) {
			return Value.NONE;
		}
		return read(player.getStats(), type, id);
	}

	private static <T> Value read(StatsCounter counter, StatType<T> type, Identifier id) {
		Optional<Holder.Reference<T>> h = type.getRegistry().get(id);
		return h.<Value>map(ref -> Value.num(counter.getValue(type, ref.value()))).orElse(Value.NONE);
	}

	/**
	 * Ids for a registry kind used by fields and signal parameters. {@code params} gives context, e.g. the
	 * statistic category for kind "stat".
	 */
	public static List<String> ids(String kind, Map<String, String> params) {
		Set<String> out = new TreeSet<>();
		switch (kind == null ? "" : kind) {
			case "stat" -> {
				StatType<?> type = statType(params == null ? "custom" : params.getOrDefault("category", "custom"));
				if (type != null) {
					type.getRegistry().keySet().forEach(i -> out.add(i.toString()));
				}
			}
			case "item" -> keys(BuiltInRegistries.ITEM, out);
			case "block" -> keys(BuiltInRegistries.BLOCK, out);
			case "entity_type" -> keys(BuiltInRegistries.ENTITY_TYPE, out);
			case "mob_effect" -> keys(BuiltInRegistries.MOB_EFFECT, out);
			case "sound_event" -> keys(BuiltInRegistries.SOUND_EVENT, out);
			case "biome", "damage_type", "dimension" -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.level != null) {
					var access = mc.level.registryAccess();
					switch (kind) {
						case "biome" -> access.lookup(Registries.BIOME).ifPresent(r -> keys(r, out));
						case "damage_type" -> access.lookup(Registries.DAMAGE_TYPE).ifPresent(r -> keys(r, out));
						default -> access.lookup(Registries.DIMENSION_TYPE).ifPresent(r -> keys(r, out));
					}
				}
				if (kind.equals("dimension")) {
					out.add("minecraft:overworld");
					out.add("minecraft:the_nether");
					out.add("minecraft:the_end");
				}
			}
			default -> {
			}
		}
		return new ArrayList<>(out);
	}

	private static void keys(Registry<?> reg, Set<String> out) {
		reg.keySet().forEach(i -> out.add(i.toString()));
	}
}
