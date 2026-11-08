package dev.sculkwire.core.signal;

import dev.sculkwire.core.util.Clock;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import java.time.ZonedDateTime;
import java.util.Locale;

import static dev.sculkwire.core.value.ValueType.BOOL;
import static dev.sculkwire.core.value.ValueType.ENUM;
import static dev.sculkwire.core.value.ValueType.ID;
import static dev.sculkwire.core.value.ValueType.LIST;
import static dev.sculkwire.core.value.ValueType.NUMBER;
import static dev.sculkwire.core.value.ValueType.STRING;

/** The built-in signal catalog (specs). Game sources are attached by the client. */
public final class BuiltinSignals {
	public static final String PLAYER = "player";
	public static final String WORLD = "world";
	public static final String NEARBY = "nearby";
	public static final String SERVER = "server";
	public static final String SESSION = "session";
	public static final String HUD = "hud";
	public static final String CLIENT = "client";

	private BuiltinSignals() {
	}

	public static void defineAll(SignalRegistry r) {
		// ---- player ----
		s(r, "player.name", STRING, PLAYER, "Your player name");
		s(r, "player.uuid", STRING, PLAYER, "Your player UUID");
		n(r, "player.health", PLAYER, "Health points (20 = 10 hearts)", "HP");
		n(r, "player.max_health", PLAYER, "Maximum health points", "HP");
		n(r, "player.health_percent", PLAYER, "Health as a percentage of maximum", "%");
		n(r, "player.absorption", PLAYER, "Absorption (golden hearts) points", "HP");
		n(r, "player.food", PLAYER, "Hunger bar (0–20)", "");
		n(r, "player.saturation", PLAYER, "Hidden saturation level", "");
		n(r, "player.air", PLAYER, "Remaining air (0–300 ticks)", "ticks");
		n(r, "player.air_percent", PLAYER, "Remaining air as a percentage", "%");
		n(r, "player.armor", PLAYER, "Armor points", "");
		n(r, "player.xp_level", PLAYER, "Experience level", "");
		n(r, "player.xp_progress", PLAYER, "Progress to the next level (0–1)", "");
		n(r, "player.xp_total", PLAYER, "Total experience points", "");
		n(r, "player.x", PLAYER, "X coordinate", "");
		n(r, "player.y", PLAYER, "Y coordinate", "");
		n(r, "player.z", PLAYER, "Z coordinate", "");
		s(r, "player.block_pos", STRING, PLAYER, "Block position as \"x y z\"");
		r.define(SignalSpec.builder("player.facing", ENUM, PLAYER, "Horizontal facing direction")
				.options("north", "east", "south", "west").build());
		r.define(SignalSpec.builder("player.dimension", ID, PLAYER, "Current dimension").registry("dimension").build());
		r.define(SignalSpec.builder("player.biome", ID, PLAYER, "Biome at your feet").registry("biome").build());
		n(r, "player.light.block", PLAYER, "Block light level at your feet (0–15)", "");
		n(r, "player.light.sky", PLAYER, "Sky light level at your feet (0–15)", "");
		b(r, "player.on_ground", PLAYER, "Standing on the ground");
		b(r, "player.in_water", PLAYER, "In water");
		b(r, "player.in_lava", PLAYER, "In lava");
		b(r, "player.on_fire", PLAYER, "On fire");
		b(r, "player.sneaking", PLAYER, "Sneaking");
		b(r, "player.sprinting", PLAYER, "Sprinting");
		b(r, "player.swimming", PLAYER, "Swimming");
		b(r, "player.flying", PLAYER, "Flying (creative/spectator)");
		b(r, "player.gliding", PLAYER, "Gliding with an elytra");
		b(r, "player.sleeping", PLAYER, "Sleeping in a bed");
		b(r, "player.is_riding", PLAYER, "Riding an entity or vehicle");
		r.define(SignalSpec.builder("player.vehicle", ID, PLAYER, "What you are riding").registry("entity_type").build());
		r.define(SignalSpec.builder("player.gamemode", ENUM, PLAYER, "Game mode")
				.options("survival", "creative", "adventure", "spectator").build());
		n(r, "player.fall_distance", PLAYER, "Current fall distance", "blocks");
		n(r, "player.speed", PLAYER, "Horizontal speed", "blocks/s");
		ParamSpec effect = ParamSpec.id("effect", "mob_effect", "speed", "Effect");
		r.define(SignalSpec.builder("player.effect.active", BOOL, PLAYER, "Whether an effect is active").param(effect).build());
		r.define(SignalSpec.builder("player.effect.level", NUMBER, PLAYER, "Level of an effect (0 when not active)").param(effect).build());
		r.define(SignalSpec.builder("player.effect.seconds", NUMBER, PLAYER, "Seconds left on an effect").param(effect).unit("s").build());
		s(r, "player.effects", LIST, PLAYER, "Active effect ids");
		r.define(SignalSpec.builder("player.held.item", ID, PLAYER, "Item in your main hand").registry("item").build());
		n(r, "player.held.count", PLAYER, "Stack size in your main hand", "");
		n(r, "player.held.durability", PLAYER, "Durability left on the main-hand item", "");
		n(r, "player.held.durability_percent", PLAYER, "Durability left on the main-hand item", "%");
		r.define(SignalSpec.builder("player.offhand.item", ID, PLAYER, "Item in your off hand").registry("item").build());
		ParamSpec slot = ParamSpec.enumOf("slot", "chest", "Armor slot", "head", "chest", "legs", "feet");
		r.define(SignalSpec.builder("player.armor_slot.item", ID, PLAYER, "Item in an armor slot").param(slot).registry("item").build());
		r.define(SignalSpec.builder("player.armor_slot.durability_percent", NUMBER, PLAYER, "Durability left on an armor piece")
				.param(slot).unit("%").build());
		n(r, "player.inventory.free_slots", PLAYER, "Empty slots in the main inventory (36 slots)", "");
		b(r, "player.inventory.full", PLAYER, "Main inventory has no empty slot");
		r.define(SignalSpec.builder("player.inventory.count", NUMBER, PLAYER, "How many of an item (or tag) you carry")
				.param(ParamSpec.id("item", "item", "diamond", "Item or #tag")).build());
		r.define(SignalSpec.builder("player.looking_at.block", ID, PLAYER, "Block under the crosshair").registry("block").build());
		r.define(SignalSpec.builder("player.looking_at.entity", ID, PLAYER, "Entity under the crosshair").registry("entity_type").build());

		// ---- world ----
		n(r, "world.time_of_day", WORLD, "Time of day in ticks (0–23999, 0 = 06:00)", "ticks");
		s(r, "world.clock", STRING, WORLD, "Time of day as HH:MM");
		n(r, "world.day", WORLD, "Day number", "");
		b(r, "world.is_day", WORLD, "It is day (not night)");
		b(r, "world.is_night", WORLD, "It is night (13000–22999)");
		b(r, "world.can_sleep", WORLD, "A bed can be used now");
		n(r, "world.ticks_until_night", WORLD, "Ticks until night starts", "ticks");
		n(r, "world.ticks_until_day", WORLD, "Ticks until day starts", "ticks");
		n(r, "world.moon_phase", WORLD, "Moon phase (0 = full moon … 7)", "");
		r.define(SignalSpec.builder("world.weather", ENUM, WORLD, "Weather").options("clear", "rain", "thunder").build());
		r.define(SignalSpec.builder("world.difficulty", ENUM, WORLD, "Difficulty").options("peaceful", "easy", "normal", "hard").build());
		b(r, "world.hardcore", WORLD, "Hardcore world");
		s(r, "world.name", STRING, WORLD, "Singleplayer world name, or the server address");
		b(r, "world.is_singleplayer", WORLD, "Playing in singleplayer");

		// ---- nearby ----
		r.define(SignalSpec.builder("nearby.hostiles", NUMBER, NEARBY, "Hostile mobs within a radius")
				.param(radius(16)).expensive(20).build());
		r.define(SignalSpec.builder("nearby.players", NUMBER, NEARBY, "Other players within a radius")
				.param(radius(64)).expensive(20).build());
		r.define(SignalSpec.builder("nearby.player_names", LIST, NEARBY, "Names of other players within a radius")
				.param(radius(64)).expensive(20).build());
		r.define(SignalSpec.builder("nearby.entities", NUMBER, NEARBY, "Entities of a type (or #tag) within a radius")
				.param(radius(16)).param(ParamSpec.id("type", "entity_type", "", "Entity type or #tag (empty = any)"))
				.expensive(20).build());
		r.define(SignalSpec.builder("nearby.nearest_hostile_distance", NUMBER, NEARBY, "Distance to the nearest hostile mob")
				.param(radius(32)).unit("blocks").expensive(20).build());

		// ---- server ----
		b(r, "server.connected", SERVER, "Connected to a world or server");
		s(r, "server.address", STRING, SERVER, "Server address (empty in singleplayer)");
		s(r, "server.name", STRING, SERVER, "Server name from your server list");
		n(r, "server.ping", SERVER, "Your ping", "ms");
		n(r, "server.tps_estimate", SERVER, "Estimated server ticks per second", "TPS");
		n(r, "server.player_count", SERVER, "Players in the tab list", "");
		s(r, "server.online_players", LIST, SERVER, "Names in the tab list");
		s(r, "server.brand", STRING, SERVER, "Server software brand");

		// ---- session ----
		n(r, "session.afk_seconds", SESSION, "Seconds since your last key or mouse input", "s");
		b(r, "session.window_focused", SESSION, "The game window has focus");
		r.define(SignalSpec.builder("session.actively_playing", BOOL, SESSION,
				"You are actively playing: the window has focus and you gave input recently")
				.param(ParamSpec.number("idle_seconds", 30, 1, 3600, "Idle after (s)")).build());
		r.define(SignalSpec.builder("session.screen", ENUM, SESSION, "Open screen")
				.options("none", "chat", "inventory", "container", "pause", "death", "other").build());
		n(r, "session.playtime_seconds", SESSION, "Seconds since joining this world or server", "s");
		n(r, "session.deaths", SESSION, "Deaths since joining", "");
		n(r, "session.real_hour", SESSION, "Real-world hour (0–23)", "");
		s(r, "session.real_time", STRING, SESSION, "Real-world time as HH:MM");
		r.define(SignalSpec.builder("session.weekday", ENUM, SESSION, "Real-world weekday")
				.options("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").build());

		// ---- hud ----
		s(r, "hud.actionbar", STRING, HUD, "Text in the action bar (above the hotbar)");
		s(r, "hud.title", STRING, HUD, "Title text on screen");
		s(r, "hud.subtitle", STRING, HUD, "Subtitle text on screen");
		s(r, "hud.bossbars", LIST, HUD, "Boss bar names");
		r.define(SignalSpec.builder("hud.bossbar.progress", NUMBER, HUD, "Progress of a boss bar whose name matches a regex (0–1)")
				.param(ParamSpec.string("name", ".*", "Name (regex)")).build());
		s(r, "hud.sidebar", LIST, HUD, "Scoreboard sidebar lines");

		// ---- client ----
		n(r, "client.fps", CLIENT, "Frames per second", "FPS");
		n(r, "client.memory_percent", CLIENT, "Java memory in use", "%");
	}

	/** Sources that need no game access: the real-world clock. */
	public static void registerCoreSources(SignalRegistry r, Clock clock) {
		r.source("session.real_hour", p -> Value.num(clock.now().getHour()));
		r.source("session.real_time", p -> {
			ZonedDateTime t = clock.now();
			return Value.str(String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute()));
		});
		r.source("session.weekday", p -> Value.enumOf(clock.now().getDayOfWeek().name()));
	}

	private static ParamSpec radius(double def) {
		return ParamSpec.number("radius", def, 1, 128, "Radius (blocks)");
	}

	private static void n(SignalRegistry r, String id, String cat, String desc, String unit) {
		r.define(SignalSpec.builder(id, NUMBER, cat, desc).unit(unit).build());
	}

	private static void b(SignalRegistry r, String id, String cat, String desc) {
		r.define(SignalSpec.builder(id, BOOL, cat, desc).build());
	}

	private static void s(SignalRegistry r, String id, ValueType type, String cat, String desc) {
		r.define(SignalSpec.builder(id, type, cat, desc).build());
	}
}
