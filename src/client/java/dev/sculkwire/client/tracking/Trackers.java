package dev.sculkwire.client.tracking;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.client.mixin.BossHealthOverlayAccessor;
import dev.sculkwire.core.template.Filters;
import dev.sculkwire.core.value.Value;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side state that the game does not keep for us (AFK time, TPS, last titles, session stats) and
 * the per-tick comparisons that turn state changes into events (dimension, biome, level, damage,
 * respawn, tab list, boss bars, item pickups).
 */
public final class Trackers {
	private static final long TITLE_MS = 5_000;
	private static final long ACTIONBAR_MS = 3_000;

	private final ClientRuntime rt;

	// afk
	private long lastInputAt = System.currentTimeMillis();
	private double lastMx = Double.NaN;
	private double lastMy = Double.NaN;
	private Screen lastScreen;

	// tps
	private final ArrayDeque<long[]> timeSamples = new ArrayDeque<>();
	private double tps = Double.NaN;

	// session
	private boolean inWorld;
	private long joinedAt;
	private int deaths;
	private long graceUntil;
	private String lastServerAddress = "";
	private boolean disconnectPosted = true;
	private double endedPlaytime;
	private long primeUntilTick;

	// hud
	private String actionbar = "";
	private long actionbarUntil;
	private String title = "";
	private String subtitle = "";
	private long titleUntil;
	private Set<String> bossbars = new LinkedHashSet<>();

	// diffs
	private LocalPlayer lastPlayer;
	private String dimension;
	private String biome;
	private int xpLevel = -1;
	private float health = -1;
	private boolean dead;
	private Set<String> tabNames;
	private Map<String, Integer> invCounts;
	private long tick;
	private double engineNanos;

	// damage info from the damage-event packet
	private String lastDamageSource = "unknown";
	private String lastAttacker = "";
	private long lastDamageAt;

	public Trackers(ClientRuntime rt) {
		this.rt = rt;
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	// ---- lifecycle ----

	public void onJoin(Minecraft mc) {
		inWorld = true;
		joinedAt = now();
		deaths = 0;
		graceUntil = now() + 3_000;
		resetDiffs();
		timeSamples.clear();
		tps = Double.NaN;
		lastInputAt = now();
		lastServerAddress = mc.getCurrentServer() != null && !mc.hasSingleplayerServer() ? mc.getCurrentServer().ip : "";
		disconnectPosted = false;
		rt.engine.onJoin();
		Map<String, Value> p = new LinkedHashMap<>();
		p.put("server.address", Value.str(lastServerAddress));
		p.put("world.name", Value.str(worldName(mc)));
		rt.post("connection.joined", p);
	}

	public void onDisconnect() {
		endedPlaytime = playtimeSeconds();
		inWorld = false;
		resetDiffs();
		bossbars = new LinkedHashSet<>();
		title = subtitle = actionbar = "";
		rt.engine.onDisconnect();
	}

	/** Called from the disconnect mixin, before {@link #onDisconnect()}. */
	public void onDisconnectReason(Component reason, boolean voluntary) {
		if (disconnectPosted) {
			return;
		}
		disconnectPosted = true;
		Map<String, Value> p = new LinkedHashMap<>();
		p.put("reason", Value.str(reason == null ? "" : Filters.stripFormatting(reason.getString())));
		p.put("server.address", Value.str(lastServerAddress));
		p.put("session.playtime_seconds", Value.num(Math.floor(inWorld ? playtimeSeconds() : endedPlaytime)));
		p.put("voluntary", Value.bool(voluntary));
		rt.post("connection.disconnected", p);
	}

	private void resetDiffs() {
		lastPlayer = null;
		dimension = null;
		biome = null;
		xpLevel = -1;
		health = -1;
		dead = false;
		tabNames = null;
		invCounts = null;
	}

	// ---- per tick ----

	public void tick(Minecraft mc) {
		tick++;
		trackInput(mc);
		long now = now();
		if (now > titleUntil) {
			title = "";
			subtitle = "";
		}
		if (now > actionbarUntil) {
			actionbar = "";
		}
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player != null && level != null && inWorld) {
			try {
				diffs(mc, player, level, now);
			} catch (RuntimeException e) {
				// a broken comparison must never break the game tick
			}
		}
		long t0 = System.nanoTime();
		rt.engine.tick();
		engineNanos = engineNanos * 0.98 + (System.nanoTime() - t0) * 0.02;
	}

	public double engineMicros() {
		return engineNanos / 1000.0;
	}

	private void trackInput(Minecraft mc) {
		double mx = mc.mouseHandler.xpos();
		double my = mc.mouseHandler.ypos();
		boolean moved = !Double.isNaN(lastMx) && (Math.abs(mx - lastMx) > 0.5 || Math.abs(my - lastMy) > 0.5);
		lastMx = mx;
		lastMy = my;
		Screen screen = mc.gui.screen();
		boolean screenChanged = screen != lastScreen;
		lastScreen = screen;
		boolean key = false;
		for (KeyMapping k : new KeyMapping[] {mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
				mc.options.keyJump, mc.options.keyShift, mc.options.keyAttack, mc.options.keyUse, mc.options.keySprint}) {
			if (k.isDown()) {
				key = true;
				break;
			}
		}
		if (moved || key || screenChanged) {
			lastInputAt = now();
		}
	}

	private void diffs(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
		boolean graceOver = now > graceUntil;

		String dim = level.dimension().identifier().toString();
		if (dimension != null && !dimension.equals(dim)) {
			rt.engine.onDimensionChange();
			Map<String, Value> p = new LinkedHashMap<>();
			p.put("old", Value.id(dimension));
			p.put("new", Value.id(dim));
			rt.post("player.dimension_changed", p);
			biome = null;
		}
		dimension = dim;

		if (player != lastPlayer) {
			// a new player object (join, respawn, dimension change) starts with default values until the
			// server sends the real ones: watch quietly for 2 seconds
			lastPlayer = player;
			primeUntilTick = tick + 40;
		}
		boolean priming = tick < primeUntilTick;

		float h = player.getHealth();
		if (!priming && health >= 0 && h < health - 0.01f) {
			Map<String, Value> p = new LinkedHashMap<>();
			p.put("damage.amount", Value.num(Math.round((health - h) * 10) / 10.0));
			boolean recent = now - lastDamageAt < 1_500;
			p.put("damage.source", Value.str(recent ? lastDamageSource : "unknown"));
			p.put("damage.attacker", Value.str(recent ? lastAttacker : ""));
			p.put("health.after", Value.num(h));
			rt.post("player.damaged", p);
		}
		health = h;

		if (dead && player.isAlive() && h > 0) {
			dead = false;
			rt.post("player.respawn", Map.of("dimension", Value.id(dim)));
		}

		if (tick % 10 == 0) {
			String b = level.getBiome(player.blockPosition()).getRegisteredName();
			if (biome != null && !biome.equals(b)) {
				rt.post("player.biome_changed", Map.of("old", Value.id(biome), "new", Value.id(b)));
			}
			biome = b;

			trackTabList(mc, player, graceOver);
			trackBossbars(mc);
		}

		if (tick % 5 == 0 && rt.listensTo("player.item_picked_up")) {
			Map<String, Integer> counts = new HashMap<>();
			List<ItemStack> items = player.getInventory().getNonEquipmentItems();
			for (ItemStack s : items) {
				addCount(counts, s);
			}
			addCount(counts, player.getOffhandItem());
			if (invCounts != null && graceOver && mc.gui.screen() == null) {
				for (Map.Entry<String, Integer> e : counts.entrySet()) {
					int before = invCounts.getOrDefault(e.getKey(), 0);
					if (e.getValue() > before) {
						rt.post("player.item_picked_up", Map.of("item", Value.id(e.getKey()), "count", Value.num(e.getValue() - before)));
					}
				}
			}
			invCounts = counts;
		} else if (tick % 5 == 0) {
			invCounts = null;
		}
	}

	private static void addCount(Map<String, Integer> counts, ItemStack s) {
		if (!s.isEmpty()) {
			counts.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
		}
	}

	private void trackTabList(Minecraft mc, LocalPlayer player, boolean graceOver) {
		if (mc.getConnection() == null) {
			return;
		}
		Set<String> names = new LinkedHashSet<>();
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			names.add(info.getProfile().name());
		}
		String me = player.getGameProfile().name();
		if (tabNames != null && graceOver) {
			for (String n : names) {
				if (!tabNames.contains(n) && !n.equals(me)) {
					rt.post("server.player_joined", Map.of("other.name", Value.str(n)));
				}
			}
			for (String n : tabNames) {
				if (!names.contains(n) && !n.equals(me)) {
					rt.post("server.player_left", Map.of("other.name", Value.str(n)));
				}
			}
		}
		if (graceOver || tabNames == null) {
			tabNames = names;
		}
	}

	private void trackBossbars(Minecraft mc) {
		Set<String> now = new LinkedHashSet<>();
		for (LerpingBossEvent e : bossEvents(mc)) {
			now.add(Filters.stripFormatting(e.getName().getString()));
		}
		for (String n : now) {
			if (!bossbars.contains(n)) {
				rt.post("hud.bossbar_added", Map.of("name", Value.str(n)));
			}
		}
		for (String n : bossbars) {
			if (!now.contains(n)) {
				rt.post("hud.bossbar_removed", Map.of("name", Value.str(n)));
			}
		}
		bossbars = now;
	}

	public static java.util.Collection<LerpingBossEvent> bossEvents(Minecraft mc) {
		return ((BossHealthOverlayAccessor) mc.gui.hud.getBossOverlay()).sculkwire$events().values();
	}

	// ---- hooks from mixins and Fabric events ----

	public void onTimePacket(long gameTime) {
		long now = now();
		timeSamples.addLast(new long[] {gameTime, now});
		while (timeSamples.size() > 12) {
			timeSamples.removeFirst();
		}
		long[] first = timeSamples.peekFirst();
		long[] last = timeSamples.peekLast();
		if (first != null && last != null && last[1] - first[1] >= 2_000) {
			double seconds = (last[1] - first[1]) / 1000.0;
			tps = Math.max(0, (last[0] - first[0]) / seconds);
		}
	}

	/**
	 * Level ups come from the server's experience packet: a new player object (respawn, dimension change)
	 * briefly reports level 0 until that packet arrives, so polling would see false level ups.
	 */
	public void onExperiencePacket(int level) {
		if (xpLevel >= 0 && level > xpLevel && inWorld) {
			rt.post("player.level_up", Map.of("old", Value.num(xpLevel), "new", Value.num(level)));
		}
		xpLevel = level;
	}

	public void onDamageEvent(String sourceType, String attacker) {
		lastDamageSource = sourceType;
		lastAttacker = attacker == null ? "" : attacker;
		lastDamageAt = now();
	}

	public void onDeath(Component message) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		dead = true;
		deaths++;
		Map<String, Value> payload = new LinkedHashMap<>();
		payload.put("death.message", Value.str(message == null ? "" : Filters.stripFormatting(message.getString())));
		if (p != null) {
			payload.put("death.x", Value.num(p.getX()));
			payload.put("death.y", Value.num(p.getY()));
			payload.put("death.z", Value.num(p.getZ()));
			payload.put("death.dimension", Value.id(p.level().dimension().identifier().toString()));
		}
		rt.post("player.death", payload);
	}

	public void onTotem() {
		LocalPlayer p = Minecraft.getInstance().player;
		rt.post("player.totem_used", Map.of("health.after", Value.num(p == null ? 0 : p.getHealth())));
	}

	public void onItemBroken(ItemStack stack, EquipmentSlot slot) {
		rt.post("player.item_broken", Map.of("item", Value.id(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
				"slot", Value.enumOf(slot.getName())));
	}

	public void onTitle(Component c) {
		title = c == null ? "" : Filters.stripFormatting(c.getString());
		titleUntil = now() + TITLE_MS;
		if (!title.isEmpty()) {
			rt.post("hud.title", Map.of("text", Value.str(title)));
		}
	}

	public void onSubtitle(Component c) {
		subtitle = c == null ? "" : Filters.stripFormatting(c.getString());
		titleUntil = now() + TITLE_MS;
		if (!subtitle.isEmpty()) {
			rt.post("hud.subtitle", Map.of("text", Value.str(subtitle)));
		}
	}

	public void onClearTitles() {
		title = "";
		subtitle = "";
	}

	public void onActionbar(Component c) {
		String text = c == null ? "" : Filters.stripFormatting(c.getString());
		boolean changed = !text.equals(actionbar) || now() > actionbarUntil;
		actionbar = text;
		actionbarUntil = now() + ACTIONBAR_MS;
		if (changed && !text.isEmpty()) {
			rt.post("hud.actionbar", Map.of("text", Value.str(text)));
		}
	}

	public void onAdvancement(String titleText, String id) {
		rt.post("player.advancement", Map.of("advancement.title", Value.str(titleText), "advancement.id", Value.str(id)));
	}

	public void onSound(Identifier id, double x, double y, double z) {
		if (!rt.listensTo("sound.played")) {
			return;
		}
		LocalPlayer p = Minecraft.getInstance().player;
		double dist = p == null ? 0 : Math.sqrt(p.distanceToSqr(x, y, z));
		Map<String, Value> payload = new LinkedHashMap<>();
		payload.put("sound.id", Value.id(id.toString()));
		payload.put("sound.x", Value.num(x));
		payload.put("sound.y", Value.num(y));
		payload.put("sound.z", Value.num(z));
		payload.put("sound.distance", Value.num(Math.round(dist * 10) / 10.0));
		rt.post("sound.played", payload);
	}

	public void onEntityLoad(Entity e, boolean loaded) {
		Minecraft mc = Minecraft.getInstance();
		if (!(e instanceof Player other) || other == mc.player || mc.player == null || now() < graceUntil) {
			return;
		}
		Map<String, Value> payload = new LinkedHashMap<>();
		payload.put("other.name", Value.str(other.getGameProfile().name()));
		if (loaded) {
			payload.put("other.distance", Value.num(Math.round(other.distanceTo(mc.player))));
			rt.post("entity.player_appeared", payload);
		} else {
			rt.post("entity.player_left", payload);
		}
	}

	public void onScreenInit(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> acs) || screen instanceof InventoryScreen
				|| screen instanceof CreativeModeInventoryScreen) {
			return;
		}
		String type = "";
		try {
			Identifier key = BuiltInRegistries.MENU.getKey(acs.getMenu().getType());
			type = key == null ? "" : key.toString();
		} catch (RuntimeException ignored) {
			// menus without a type
		}
		rt.post("player.container_opened", Map.of("screen.title", Value.str(Filters.stripFormatting(screen.getTitle().getString())),
				"container.type", Value.str(type)));
	}

	// ---- values for signals ----

	public double afkSeconds() {
		return (now() - lastInputAt) / 1000.0;
	}

	public double tps() {
		return tps;
	}

	public boolean inWorld() {
		return inWorld;
	}

	public double playtimeSeconds() {
		return inWorld ? (now() - joinedAt) / 1000.0 : 0;
	}

	public int deaths() {
		return deaths;
	}

	public String actionbar() {
		return actionbar;
	}

	public String title() {
		return title;
	}

	public String subtitle() {
		return subtitle;
	}

	public Set<String> bossbars() {
		return new HashSet<>(bossbars);
	}

	public static String worldName(Minecraft mc) {
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			return mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "";
	}
}
