package dev.sculkwire.core.event;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.value.ValueType;

import java.util.ArrayList;
import java.util.List;

import static dev.sculkwire.core.event.EventFilters.anyOf;
import static dev.sculkwire.core.event.EventFilters.max;
import static dev.sculkwire.core.event.EventFilters.min;
import static dev.sculkwire.core.event.EventFilters.regex;
import static dev.sculkwire.core.value.ValueType.BOOL;
import static dev.sculkwire.core.value.ValueType.ENUM;
import static dev.sculkwire.core.value.ValueType.ID;
import static dev.sculkwire.core.value.ValueType.NUMBER;
import static dev.sculkwire.core.value.ValueType.STRING;

/** The built-in event catalog. The client posts these from game hooks. */
public final class BuiltinEvents {
	public static final String CHAT = "chat";
	public static final String PLAYER = "player";
	public static final String WORLD = "world";
	public static final String SERVER = "server";
	public static final String HUD = "hud";
	public static final String OTHER = "other";

	private BuiltinEvents() {
	}

	public static void defineAll(EventRegistry r) {
		// ---- chat ----
		r.define(new EventSpec("chat.message", CHAT, "A chat or system message arrives",
				List.of(
						PayloadVar.of("msg.text", STRING, "Steve, come look at this", "Message text (formatting removed)"),
						PayloadVar.of("msg.full", STRING, "<Alex> Steve, come look at this", "The whole line as shown in chat"),
						PayloadVar.of("msg.sender", STRING, "Alex", "Sender name (player chat, or extracted with the sender regex)"),
						PayloadVar.of("msg.has_sender", BOOL, true,
								"It came from a player: player chat, or a name found with the sender regex"),
						PayloadVar.of("msg.sender_uuid", STRING, "", "Sender UUID (player chat only)"),
						PayloadVar.of("msg.kind", ENUM, "player", "player or system"),
						PayloadVar.of("msg.is_own", BOOL, false, "You sent it")),
				List.of(
						FieldSpec.of("mode", FieldKind.ENUM, "Match").withOptions("any", "contains", "equals", "regex", "mentions_me")
								.withDefault("contains"),
						FieldSpec.of("pattern", FieldKind.TEMPLATE, "Text or regex")
								.withHint("Text to look for; {variables} work. For regex, groups become {match.1}."),
						FieldSpec.of("source", FieldKind.ENUM, "Source").withOptions("both", "player", "system").withDefault("both")
								.withHint("player = signed player chat; system = server/plugin messages"),
						FieldSpec.of("senders", FieldKind.STRING_LIST, "Only from")
								.withHint("Player names, or a list variable like {friends}. Empty = anyone."),
						FieldSpec.of("ignore_case", FieldKind.BOOL, "Ignore case").withDefault(true),
						FieldSpec.of("ignore_own", FieldKind.BOOL, "Ignore my own messages").withDefault(true),
						FieldSpec.of("sender_regex", FieldKind.REGEX, "Sender regex")
								.withHint("For servers that send chat as system messages, e.g. ^<(?<name>\\w+)> (?<msg>.*)$")),
				new ChatFilter()));

		// ---- hud ----
		textEvent(r, "hud.actionbar", "Action bar text appears", "Sleeping through this night");
		textEvent(r, "hud.title", "A title appears on screen", "Round 3");
		textEvent(r, "hud.subtitle", "A subtitle appears on screen", "Get ready");
		r.define(new EventSpec("hud.bossbar_added", HUD, "A boss bar appears",
				List.of(PayloadVar.of("name", STRING, "Ender Dragon", "Boss bar text")),
				List.of(pattern("Name matches")), EventFilters.of(regex("pattern", "name"))));
		r.define(new EventSpec("hud.bossbar_removed", HUD, "A boss bar disappears",
				List.of(PayloadVar.of("name", STRING, "Ender Dragon", "Boss bar text")),
				List.of(pattern("Name matches")), EventFilters.of(regex("pattern", "name"))));

		// ---- player ----
		r.define(new EventSpec("player.death", PLAYER, "You died",
				List.of(
						PayloadVar.of("death.message", STRING, "Steve was slain by Zombie", "Death message"),
						PayloadVar.of("death.x", NUMBER, 120, "X"),
						PayloadVar.of("death.y", NUMBER, 64, "Y"),
						PayloadVar.of("death.z", NUMBER, -300, "Z"),
						PayloadVar.of("death.dimension", ID, "minecraft:overworld", "Dimension")),
				List.of(pattern("Death message matches")), EventFilters.of(regex("pattern", "death.message"))));
		r.define(new EventSpec("player.respawn", PLAYER, "You respawned",
				List.of(PayloadVar.of("dimension", ID, "minecraft:overworld", "Dimension")), List.of(), EventFilter.ANY));
		r.define(new EventSpec("player.damaged", PLAYER, "You took damage",
				List.of(
						PayloadVar.of("damage.amount", NUMBER, 4, "Health lost"),
						PayloadVar.of("damage.source", STRING, "minecraft:mob_attack", "Damage type"),
						PayloadVar.of("damage.attacker", STRING, "minecraft:zombie", "Attacking entity type (if any)"),
						PayloadVar.of("health.after", NUMBER, 12, "Health after the hit")),
				List.of(
						FieldSpec.of("min_amount", FieldKind.NUMBER, "At least (HP)"),
						FieldSpec.of("sources", FieldKind.ID_LIST, "Damage types").withRegistry("damage_type")
								.withHint("Empty = any")),
				EventFilters.of(min("min_amount", "damage.amount"), anyOf("sources", "damage.source"))));
		r.define(new EventSpec("player.totem_used", PLAYER, "A Totem of Undying saved you",
				List.of(PayloadVar.of("health.after", NUMBER, 1, "Health after")), List.of(), EventFilter.ANY));
		r.define(changeEvent("player.dimension_changed", "You changed dimension", ID, "minecraft:overworld",
				"minecraft:the_nether", "dimension"));
		r.define(changeEvent("player.biome_changed", "You entered another biome", ID, "minecraft:plains",
				"minecraft:forest", "biome"));
		r.define(new EventSpec("player.level_up", PLAYER, "Your experience level went up",
				List.of(PayloadVar.of("old", NUMBER, 29, "Previous level"), PayloadVar.of("new", NUMBER, 30, "New level")),
				List.of(FieldSpec.of("min_level", FieldKind.NUMBER, "Only from level")),
				EventFilters.of(min("min_level", "new"))));
		r.define(new EventSpec("player.item_broken", PLAYER, "An item you were using broke",
				List.of(PayloadVar.of("item", ID, "minecraft:diamond_pickaxe", "Item"),
						PayloadVar.of("slot", ENUM, "mainhand", "Slot")),
				List.of(items("items")), EventFilters.of(anyOf("items", "item"))));
		r.define(new EventSpec("player.advancement", PLAYER, "You made an advancement",
				List.of(PayloadVar.of("advancement.title", STRING, "Diamonds!", "Title"),
						PayloadVar.of("advancement.id", STRING, "minecraft:story/mine_diamond", "Id"),
						PayloadVar.of("advancement.type", ENUM, "task", "task, goal or challenge")),
				List.of(pattern("Title matches"), advancementTypes()),
				EventFilters.of(regex("pattern", "advancement.title"), anyOf("types", "advancement.type"))));
		EventFilter announced = EventFilters.of(anyOf("names", "other.name"), anyOf("types", "advancement.type"),
				regex("pattern", "advancement.title"));
		r.define(new EventSpec("chat.advancement", CHAT, "A player's advancement is announced in chat",
				List.of(PayloadVar.of("other.name", STRING, "Alex", "Who made it"),
						PayloadVar.of("advancement.title", STRING, "Diamonds!", "Advancement title"),
						PayloadVar.of("advancement.type", ENUM, "task", "task, goal or challenge"),
						PayloadVar.of("is_own", BOOL, false, "It was you")),
				List.of(names(), advancementTypes(), pattern("Title matches"),
						FieldSpec.of("include_own", FieldKind.BOOL, "Also my own").withDefault(false)),
				(p, ctx) -> (dev.sculkwire.core.util.Json.bool(p, "include_own", false) || !ctx.payload("is_own").asBool())
						&& announced.test(p, ctx)));
		r.define(new EventSpec("player.item_picked_up", PLAYER, "You picked up an item",
				List.of(PayloadVar.of("item", ID, "minecraft:diamond", "Item"), PayloadVar.of("count", NUMBER, 1, "Count")),
				List.of(items("items"), FieldSpec.of("min_count", FieldKind.NUMBER, "At least")),
				EventFilters.of(anyOf("items", "item"), min("min_count", "count"))));
		r.define(new EventSpec("player.container_opened", PLAYER, "You opened a container",
				List.of(PayloadVar.of("screen.title", STRING, "Chest", "Screen title"),
						PayloadVar.of("container.type", STRING, "minecraft:generic_9x3", "Menu type")),
				List.of(pattern("Title matches")), EventFilters.of(regex("pattern", "screen.title"))));

		// ---- world / other players ----
		r.define(playerEvent("entity.player_appeared", "Another player came into render distance", true));
		r.define(playerEvent("entity.player_left", "Another player left render distance", false));
		r.define(new EventSpec("sound.played", WORLD, "A sound plays near you",
				List.of(PayloadVar.of("sound.id", ID, "minecraft:event.raid.horn", "Sound id"),
						PayloadVar.of("sound.x", NUMBER, 0, "X"),
						PayloadVar.of("sound.y", NUMBER, 64, "Y"),
						PayloadVar.of("sound.z", NUMBER, 0, "Z"),
						PayloadVar.of("sound.distance", NUMBER, 12, "Distance (blocks)")),
				List.of(FieldSpec.of("sounds", FieldKind.ID_LIST, "Sounds").withRegistry("sound_event")
								.withHint("Sound ids; empty = any (noisy!)"),
						FieldSpec.of("max_distance", FieldKind.NUMBER, "Within (blocks)")),
				EventFilters.of(anyOf("sounds", "sound.id"), max("max_distance", "sound.distance"))));

		// ---- server / connection ----
		r.define(new EventSpec("server.player_joined", SERVER, "A player joined the server (tab list)",
				List.of(PayloadVar.of("other.name", STRING, "Alex", "Player name")),
				List.of(names()), EventFilters.of(anyOf("names", "other.name"))));
		r.define(new EventSpec("server.player_left", SERVER, "A player left the server (tab list)",
				List.of(PayloadVar.of("other.name", STRING, "Alex", "Player name")),
				List.of(names()), EventFilters.of(anyOf("names", "other.name"))));
		r.define(new EventSpec("connection.joined", SERVER, "You joined a world or server",
				List.of(PayloadVar.of("server.address", STRING, "play.example.net", "Server address"),
						PayloadVar.of("world.name", STRING, "play.example.net", "World or server")),
				List.of(), EventFilter.ANY));
		EventFilter reasonFilter = EventFilters.of(regex("pattern", "reason"));
		r.define(new EventSpec("connection.disconnected", SERVER, "You were disconnected or kicked",
				List.of(PayloadVar.of("reason", STRING, "Server closed", "Disconnect reason"),
						PayloadVar.of("server.address", STRING, "play.example.net", "Server address"),
						PayloadVar.of("session.playtime_seconds", NUMBER, 5400, "Seconds played"),
						PayloadVar.of("voluntary", BOOL, false, "You left yourself (Disconnect / Save and Quit)")),
				List.of(pattern("Reason matches"),
						FieldSpec.of("include_quit", FieldKind.BOOL, "Also when I leave myself").withDefault(false)),
				(p, ctx) -> (dev.sculkwire.core.util.Json.bool(p, "include_quit", false) || !ctx.payload("voluntary").asBool())
						&& reasonFilter.test(p, ctx)));

		// ---- other ----
		r.define(new EventSpec("client.started", OTHER, "The game finished starting", List.of(), List.of(), EventFilter.ANY));
		r.define(new EventSpec("client.stopping", OTHER, "The game is closing", List.of(), List.of(), EventFilter.ANY));
		r.define(new EventSpec("custom", OTHER, "A custom event from /sculkwire emit or another mod",
				List.of(PayloadVar.of("name", STRING, "my_event", "Event name"), PayloadVar.of("text", STRING, "hello", "Text")),
				List.of(FieldSpec.of("names", FieldKind.STRING_LIST, "Event names").withHint("Empty = any")),
				EventFilters.of(anyOf("names", "name"))));
	}

	private static void textEvent(EventRegistry r, String id, String desc, String sample) {
		r.define(new EventSpec(id, HUD, desc, List.of(PayloadVar.of("text", STRING, sample, "Text")),
				List.of(pattern("Text matches")), EventFilters.of(regex("pattern", "text"))));
	}

	private static EventSpec changeEvent(String id, String desc, ValueType type, Object oldSample, Object newSample, String registry) {
		List<PayloadVar> payload = new ArrayList<>();
		payload.add(PayloadVar.of("old", type, oldSample, "Before"));
		payload.add(PayloadVar.of("new", type, newSample, "After"));
		return new EventSpec(id, PLAYER, desc, payload,
				List.of(FieldSpec.of("from", FieldKind.ID_LIST, "From").withRegistry(registry).withHint("Empty = any"),
						FieldSpec.of("to", FieldKind.ID_LIST, "To").withRegistry(registry).withHint("Empty = any")),
				EventFilters.of(anyOf("from", "old"), anyOf("to", "new")));
	}

	private static EventSpec playerEvent(String id, String desc, boolean withDistance) {
		List<PayloadVar> payload = new ArrayList<>();
		payload.add(PayloadVar.of("other.name", STRING, "Alex", "Player name"));
		if (withDistance) {
			payload.add(PayloadVar.of("other.distance", NUMBER, 40, "Distance (blocks)"));
		}
		return new EventSpec(id, WORLD, desc, payload, List.of(names()), EventFilters.of(anyOf("names", "other.name")));
	}

	private static FieldSpec pattern(String label) {
		return FieldSpec.of("pattern", FieldKind.REGEX, label).withHint("Regex; empty = anything. Groups become {match.1}.");
	}

	private static FieldSpec advancementTypes() {
		return FieldSpec.of("types", FieldKind.STRING_LIST, "Only types")
				.withHint("task, goal, challenge (empty = any). Challenges are the purple ones.");
	}

	private static FieldSpec names() {
		return FieldSpec.of("names", FieldKind.STRING_LIST, "Only these players")
				.withHint("Names, or a list variable like {friends}. Empty = anyone.");
	}

	private static FieldSpec items(String key) {
		return FieldSpec.of(key, FieldKind.ID_LIST, "Items").withRegistry("item").withHint("Item ids or #tags; empty = any");
	}
}
