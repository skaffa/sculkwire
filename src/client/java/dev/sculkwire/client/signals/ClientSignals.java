package dev.sculkwire.client.signals;

import dev.sculkwire.client.tracking.Trackers;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.template.Filters;
import dev.sculkwire.core.value.Value;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Game-side sources for the built-in signal catalog. Everything runs on the client thread. */
public final class ClientSignals {
	private ClientSignals() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static void p(SignalRegistry r, String id, Function<LocalPlayer, Value> f) {
		r.source(id, params -> {
			LocalPlayer pl = mc().player;
			return pl == null ? Value.NONE : f.apply(pl);
		});
	}

	private static void pp(SignalRegistry r, String id, java.util.function.BiFunction<LocalPlayer, Map<String, Value>, Value> f) {
		r.source(id, params -> {
			LocalPlayer pl = mc().player;
			return pl == null ? Value.NONE : f.apply(pl, params);
		});
	}

	private static void w(SignalRegistry r, String id, Function<ClientLevel, Value> f) {
		r.source(id, params -> {
			ClientLevel l = mc().level;
			return l == null ? Value.NONE : f.apply(l);
		});
	}

	private static long dayTime(ClientLevel l) {
		return l.getDefaultClockTime();
	}

	private static long timeOfDay(ClientLevel l) {
		return Math.floorMod(dayTime(l), 24000L);
	}

	public static void register(SignalRegistry r, Trackers tr) {
		// ---- player ----
		r.source("player.name", params -> Value.str(mc().getGameProfile().name()));
		r.source("player.uuid", params -> Value.str(mc().getGameProfile().id().toString()));
		p(r, "player.health", pl -> Value.num(pl.getHealth()));
		p(r, "player.max_health", pl -> Value.num(pl.getMaxHealth()));
		p(r, "player.health_percent", pl -> Value.num(Math.round(pl.getHealth() / Math.max(1, pl.getMaxHealth()) * 1000) / 10.0));
		p(r, "player.absorption", pl -> Value.num(pl.getAbsorptionAmount()));
		p(r, "player.food", pl -> Value.num(pl.getFoodData().getFoodLevel()));
		p(r, "player.saturation", pl -> Value.num(pl.getFoodData().getSaturationLevel()));
		p(r, "player.air", pl -> Value.num(Math.max(0, pl.getAirSupply())));
		p(r, "player.air_percent", pl -> Value.num(Math.round(Math.max(0, pl.getAirSupply()) * 100.0 / Math.max(1, pl.getMaxAirSupply()))));
		p(r, "player.armor", pl -> Value.num(pl.getArmorValue()));
		p(r, "player.xp_level", pl -> Value.num(pl.experienceLevel));
		p(r, "player.xp_progress", pl -> Value.num(pl.experienceProgress));
		p(r, "player.xp_total", pl -> Value.num(pl.totalExperience));
		p(r, "player.x", pl -> Value.num(pl.getX()));
		p(r, "player.y", pl -> Value.num(pl.getY()));
		p(r, "player.z", pl -> Value.num(pl.getZ()));
		p(r, "player.block_pos", pl -> {
			BlockPos b = pl.blockPosition();
			return Value.str(b.getX() + " " + b.getY() + " " + b.getZ());
		});
		p(r, "player.facing", pl -> Value.enumOf(pl.getDirection().getName()));
		p(r, "player.dimension", pl -> Value.id(pl.level().dimension().identifier().toString()));
		p(r, "player.biome", pl -> Value.id(pl.level().getBiome(pl.blockPosition()).getRegisteredName()));
		p(r, "player.light.block", pl -> Value.num(pl.level().getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pl.blockPosition())));
		p(r, "player.light.sky", pl -> Value.num(pl.level().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pl.blockPosition())));
		p(r, "player.on_ground", pl -> Value.bool(pl.onGround()));
		p(r, "player.in_water", pl -> Value.bool(pl.isInWater()));
		p(r, "player.in_lava", pl -> Value.bool(pl.isInLava()));
		p(r, "player.on_fire", pl -> Value.bool(pl.isOnFire()));
		p(r, "player.sneaking", pl -> Value.bool(pl.isShiftKeyDown()));
		p(r, "player.sprinting", pl -> Value.bool(pl.isSprinting()));
		p(r, "player.swimming", pl -> Value.bool(pl.isSwimming()));
		p(r, "player.flying", pl -> Value.bool(pl.getAbilities().flying));
		p(r, "player.gliding", pl -> Value.bool(pl.isFallFlying()));
		p(r, "player.sleeping", pl -> Value.bool(pl.isSleeping()));
		p(r, "player.is_riding", pl -> Value.bool(pl.isPassenger()));
		p(r, "player.vehicle", pl -> pl.getVehicle() == null ? Value.NONE : Value.id(EntityType.getKey(pl.getVehicle().getType()).toString()));
		r.source("player.gamemode", params -> mc().gameMode == null || mc().player == null ? Value.NONE
				: Value.enumOf(mc().gameMode.getPlayerMode().getName()));
		p(r, "player.fall_distance", pl -> Value.num(Math.round(pl.fallDistance * 10) / 10.0));
		p(r, "player.speed", pl -> {
			var v = pl.getDeltaMovement();
			return Value.num(Math.round(Math.sqrt(v.x * v.x + v.z * v.z) * 20 * 100) / 100.0);
		});
		pp(r, "player.effect.active", (pl, prm) -> Value.bool(effect(pl, prm) != null));
		pp(r, "player.effect.level", (pl, prm) -> {
			MobEffectInstance e = effect(pl, prm);
			return Value.num(e == null ? 0 : e.getAmplifier() + 1);
		});
		pp(r, "player.effect.seconds", (pl, prm) -> {
			MobEffectInstance e = effect(pl, prm);
			if (e == null) {
				return Value.num(0);
			}
			return e.isInfiniteDuration() ? Value.num(Double.POSITIVE_INFINITY) : Value.num(e.getDuration() / 20);
		});
		p(r, "player.effects", pl -> {
			List<String> out = new ArrayList<>();
			for (MobEffectInstance e : pl.getActiveEffects()) {
				out.add(e.getEffect().getRegisteredName());
			}
			return Value.list(out);
		});
		p(r, "player.held.item", pl -> itemId(pl.getMainHandItem()));
		p(r, "player.held.count", pl -> Value.num(pl.getMainHandItem().getCount()));
		p(r, "player.held.durability", pl -> durability(pl.getMainHandItem(), false));
		p(r, "player.held.durability_percent", pl -> durability(pl.getMainHandItem(), true));
		p(r, "player.offhand.item", pl -> itemId(pl.getOffhandItem()));
		pp(r, "player.armor_slot.item", (pl, prm) -> itemId(pl.getItemBySlot(slot(prm))));
		pp(r, "player.armor_slot.durability_percent", (pl, prm) -> durability(pl.getItemBySlot(slot(prm)), true));
		p(r, "player.inventory.free_slots", pl -> Value.num(freeSlots(pl)));
		p(r, "player.inventory.full", pl -> Value.bool(freeSlots(pl) == 0));
		pp(r, "player.inventory.count", (pl, prm) -> Value.num(countItems(pl, prm.getOrDefault("item", Value.NONE).asString())));
		pp(r, "player.stat", (pl, prm) -> !tr.statsKnown() ? Value.NONE : GameRegistries.stat(pl,
				prm.getOrDefault("category", Value.str("custom")).asString(), prm.getOrDefault("stat", Value.NONE).asString()));
		r.source("player.looking_at.block", params -> {
			HitResult hit = mc().hitResult;
			if (mc().level == null || !(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
				return Value.NONE;
			}
			return Value.id(BuiltInRegistries.BLOCK.getKey(mc().level.getBlockState(bh.getBlockPos()).getBlock()).toString());
		});
		r.source("player.looking_at.entity", params -> {
			HitResult hit = mc().hitResult;
			if (!(hit instanceof EntityHitResult eh) || hit.getType() != HitResult.Type.ENTITY) {
				return Value.NONE;
			}
			return Value.id(EntityType.getKey(eh.getEntity().getType()).toString());
		});

		// ---- world ----
		w(r, "world.time_of_day", l -> Value.num(timeOfDay(l)));
		w(r, "world.clock", l -> Value.str(Filters.clock(timeOfDay(l))));
		w(r, "world.day", l -> Value.num(Math.floorDiv(dayTime(l), 24000L)));
		w(r, "world.is_night", l -> Value.bool(isNight(timeOfDay(l))));
		w(r, "world.is_day", l -> Value.bool(!isNight(timeOfDay(l))));
		w(r, "world.can_sleep", l -> {
			long t = timeOfDay(l);
			return Value.bool(l.isThundering() || (t >= 12542 && t < 23460));
		});
		w(r, "world.ticks_until_night", l -> {
			long t = timeOfDay(l);
			return Value.num(isNight(t) ? 0 : t < 13000 ? 13000 - t : 24000 - t + 13000);
		});
		w(r, "world.ticks_until_day", l -> {
			long t = timeOfDay(l);
			return Value.num(isNight(t) ? 23000 - t : 0);
		});
		w(r, "world.moon_phase", l -> Value.num(Math.floorMod(Math.floorDiv(dayTime(l), 24000L), 8L)));
		w(r, "world.weather", l -> Value.enumOf(l.isThundering() ? "thunder" : l.isRaining() ? "rain" : "clear"));
		w(r, "world.difficulty", l -> Value.enumOf(l.getLevelData().getDifficulty().getSerializedName()));
		w(r, "world.hardcore", l -> Value.bool(l.getLevelData().isHardcore()));
		r.source("world.name", params -> mc().level == null ? Value.NONE : Value.str(Trackers.worldName(mc())));
		r.source("world.is_singleplayer", params -> mc().level == null ? Value.NONE : Value.bool(mc().hasSingleplayerServer()));

		// ---- nearby ----
		pp(r, "nearby.hostiles", (pl, prm) -> Value.num(hostiles(pl, radius(prm)).size()));
		pp(r, "nearby.nearest_hostile_distance", (pl, prm) -> {
			double best = Double.NaN;
			for (Entity e : hostiles(pl, radius(prm))) {
				double d = e.distanceTo(pl);
				if (Double.isNaN(best) || d < best) {
					best = d;
				}
			}
			return Double.isNaN(best) ? Value.NONE : Value.num(Math.round(best * 10) / 10.0);
		});
		pp(r, "nearby.players", (pl, prm) -> Value.num(otherPlayers(pl, radius(prm)).size()));
		pp(r, "nearby.player_names", (pl, prm) -> {
			List<String> names = new ArrayList<>();
			for (Player o : otherPlayers(pl, radius(prm))) {
				names.add(o.getGameProfile().name());
			}
			return Value.list(names);
		});
		pp(r, "nearby.entities", (pl, prm) -> {
			double rad = radius(prm);
			String type = prm.getOrDefault("type", Value.NONE).asString();
			TagKey<EntityType<?>> tag = type.startsWith("#") ? tagKey(type) : null;
			int n = 0;
			for (Entity e : pl.level().getEntities(pl, pl.getBoundingBox().inflate(rad), e -> e.isAlive() && e.distanceTo(pl) <= rad)) {
				if (type.isEmpty() || (tag != null ? e.getType().builtInRegistryHolder().is(tag)
						: EntityType.getKey(e.getType()).toString().equals(Value.normalizeId(type)))) {
					n++;
				}
			}
			return Value.num(n);
		});

		// ---- server ----
		r.source("server.connected", params -> Value.bool(mc().getConnection() != null && mc().level != null));
		r.source("server.address", params -> {
			if (mc().level == null) {
				return Value.NONE;
			}
			return Value.str(mc().hasSingleplayerServer() || mc().getCurrentServer() == null ? "" : mc().getCurrentServer().ip);
		});
		r.source("server.name", params -> mc().getCurrentServer() == null || mc().level == null ? Value.NONE : Value.str(mc().getCurrentServer().name));
		r.source("server.ping", params -> {
			ClientPacketListener c = mc().getConnection();
			if (c == null || mc().player == null) {
				return Value.NONE;
			}
			PlayerInfo info = c.getPlayerInfo(mc().player.getUUID());
			return info == null ? Value.NONE : Value.num(info.getLatency());
		});
		r.source("server.tps_estimate", params -> Double.isNaN(tr.tps()) ? Value.NONE : Value.num(Math.round(tr.tps() * 10) / 10.0));
		r.source("server.player_count", params -> mc().getConnection() == null ? Value.NONE : Value.num(mc().getConnection().getOnlinePlayers().size()));
		r.source("server.online_players", params -> {
			ClientPacketListener c = mc().getConnection();
			if (c == null) {
				return Value.NONE;
			}
			List<String> names = new ArrayList<>();
			for (PlayerInfo info : c.getOnlinePlayers()) {
				names.add(info.getProfile().name());
			}
			names.sort(String.CASE_INSENSITIVE_ORDER);
			return Value.list(names);
		});
		r.source("server.brand", params -> mc().getConnection() == null || mc().getConnection().serverBrand() == null
				? Value.NONE : Value.str(mc().getConnection().serverBrand()));

		// ---- session ----
		r.source("session.afk_seconds", params -> Value.num(Math.floor(tr.afkSeconds())));
		r.source("session.window_focused", params -> Value.bool(mc().isWindowActive()));
		r.source("session.actively_playing", params -> {
			double idle = params.getOrDefault("idle_seconds", Value.num(30)).asNumber();
			return Value.bool(mc().player != null && mc().isWindowActive() && tr.afkSeconds() < idle);
		});
		r.source("session.screen", params -> Value.enumOf(screenKind(mc().gui.screen())));
		r.source("session.playtime_seconds", params -> tr.inWorld() ? Value.num(Math.floor(tr.playtimeSeconds())) : Value.NONE);
		r.source("session.deaths", params -> tr.inWorld() ? Value.num(tr.deaths()) : Value.NONE);

		// ---- hud ----
		r.source("hud.actionbar", params -> Value.str(tr.actionbar()));
		r.source("hud.title", params -> Value.str(tr.title()));
		r.source("hud.subtitle", params -> Value.str(tr.subtitle()));
		r.source("hud.bossbars", params -> {
			List<String> names = new ArrayList<>();
			for (LerpingBossEvent e : Trackers.bossEvents(mc())) {
				names.add(Filters.stripFormatting(e.getName().getString()));
			}
			return Value.list(names);
		});
		r.source("hud.bossbar.progress", params -> {
			String re = params.getOrDefault("name", Value.str(".*")).asString();
			Pattern p;
			try {
				p = Pattern.compile(re, Pattern.CASE_INSENSITIVE);
			} catch (RuntimeException e) {
				return Value.NONE;
			}
			for (LerpingBossEvent e : Trackers.bossEvents(mc())) {
				if (p.matcher(Filters.stripFormatting(e.getName().getString())).find()) {
					return Value.num(Math.round(e.getProgress() * 1000) / 1000.0);
				}
			}
			return Value.NONE;
		});
		w(r, "hud.sidebar", ClientSignals::sidebar);

		// ---- client ----
		r.source("client.fps", params -> Value.num(mc().getFps()));
		r.source("client.memory_percent", params -> {
			Runtime rt = Runtime.getRuntime();
			return Value.num(Math.round((rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory()));
		});
	}

	// ---- helpers ----

	static boolean isNight(long t) {
		return t >= 13000 && t < 23000;
	}

	private static double radius(Map<String, Value> prm) {
		double r = prm.getOrDefault("radius", Value.num(16)).asNumber();
		return Double.isNaN(r) ? 16 : r;
	}

	private static List<Entity> hostiles(LocalPlayer pl, double r) {
		return pl.level().getEntities(pl, pl.getBoundingBox().inflate(r),
				e -> e instanceof Enemy && e.isAlive() && e.distanceTo(pl) <= r);
	}

	private static List<Player> otherPlayers(LocalPlayer pl, double r) {
		List<Player> out = new ArrayList<>();
		for (Player o : pl.level().players()) {
			if (o != pl && o.isAlive() && o.distanceTo(pl) <= r) {
				out.add(o);
			}
		}
		return out;
	}

	private static MobEffectInstance effect(LocalPlayer pl, Map<String, Value> prm) {
		Identifier id = Identifier.tryParse(prm.getOrDefault("effect", Value.NONE).asString());
		if (id == null) {
			return null;
		}
		Optional<Holder.Reference<MobEffect>> h = BuiltInRegistries.MOB_EFFECT.get(id);
		return h.map(pl::getEffect).orElse(null);
	}

	private static Value itemId(ItemStack s) {
		return Value.id(s.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
	}

	private static Value durability(ItemStack s, boolean percent) {
		if (s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) {
			return Value.NONE;
		}
		int left = s.getMaxDamage() - s.getDamageValue();
		return percent ? Value.num(Math.round(left * 1000.0 / s.getMaxDamage()) / 10.0) : Value.num(left);
	}

	private static EquipmentSlot slot(Map<String, Value> prm) {
		return switch (prm.getOrDefault("slot", Value.str("chest")).asString()) {
			case "head" -> EquipmentSlot.HEAD;
			case "legs" -> EquipmentSlot.LEGS;
			case "feet" -> EquipmentSlot.FEET;
			default -> EquipmentSlot.CHEST;
		};
	}

	private static int freeSlots(LocalPlayer pl) {
		int free = 0;
		for (ItemStack s : pl.getInventory().getNonEquipmentItems()) {
			if (s.isEmpty()) {
				free++;
			}
		}
		return free;
	}

	private static int countItems(LocalPlayer pl, String id) {
		if (id.isEmpty()) {
			return 0;
		}
		String norm = Value.normalizeId(id);
		TagKey<Item> tag = null;
		if (norm.startsWith("#")) {
			Identifier t = Identifier.tryParse(norm.substring(1));
			if (t == null) {
				return 0;
			}
			tag = TagKey.create(Registries.ITEM, t);
		}
		int n = 0;
		List<ItemStack> stacks = new ArrayList<>(pl.getInventory().getNonEquipmentItems());
		stacks.add(pl.getOffhandItem());
		for (ItemStack s : stacks) {
			if (s.isEmpty()) {
				continue;
			}
			boolean match = tag != null ? s.typeHolder().is(tag) : BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(norm);
			if (match) {
				n += s.getCount();
			}
		}
		return n;
	}

	private static TagKey<EntityType<?>> tagKey(String tag) {
		Identifier id = Identifier.tryParse(Value.normalizeId(tag).substring(1));
		return id == null ? null : TagKey.create(Registries.ENTITY_TYPE, id);
	}

	static String screenKind(Screen s) {
		if (s == null) {
			return "none";
		}
		if (s instanceof ChatScreen) {
			return "chat";
		}
		if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) {
			return "inventory";
		}
		if (s instanceof AbstractContainerScreen<?>) {
			return "container";
		}
		if (s instanceof PauseScreen) {
			return "pause";
		}
		if (s instanceof DeathScreen) {
			return "death";
		}
		return "other";
	}

	private static Value sidebar(ClientLevel l) {
		Scoreboard sb = l.getScoreboard();
		Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (obj == null) {
			return Value.list(List.of());
		}
		List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(obj));
		entries.removeIf(PlayerScoreEntry::isHidden);
		entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed()
				.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER));
		List<String> lines = new ArrayList<>();
		lines.add(Filters.stripFormatting(obj.getDisplayName().getString()));
		for (PlayerScoreEntry e : entries.subList(0, Math.min(15, entries.size()))) {
			PlayerTeam team = sb.getPlayersTeam(e.owner());
			Component name = PlayerTeam.formatNameForTeam(team, e.ownerName());
			lines.add(Filters.stripFormatting(name.getString()).trim());
		}
		return Value.list(lines);
	}

	@SuppressWarnings("unused")
	private static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
