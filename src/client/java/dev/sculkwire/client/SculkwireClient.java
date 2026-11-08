package dev.sculkwire.client;

import dev.sculkwire.Sculkwire;
import dev.sculkwire.api.SculkwireApi;
import dev.sculkwire.client.command.SculkwireCommands;
import dev.sculkwire.core.template.Filters;
import dev.sculkwire.core.value.Value;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Client entrypoint: wires the runtime to Fabric events, commands and the key binding. */
public final class SculkwireClient implements ClientModInitializer {
	/** Builds the main screen (set by the UI package) given the parent screen. */
	public static Function<Screen, Screen> screenFactory;
	private static boolean openRequested;
	private static KeyMapping openKey;

	public static void openScreenNextTick() {
		openRequested = true;
	}

	@Override
	public void onInitializeClient() {
		ClientRuntime rt = ClientRuntime.init();
		SculkwireApi.bind(rt::post);
		screenFactory = dev.sculkwire.client.ui.SculkwireScreen::new;

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Sculkwire.MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sculkwire.open",
				InputConstants.UNKNOWN.getValue(), category));

		if (Boolean.getBoolean("sculkwire.devshots")) {
			registerDevShots();
		}

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			rt.trackers.tick(mc);
			dev.sculkwire.client.ui.DevScreenshots.tick(mc);
			rt.flushWarnings();
			while (openKey.consumeClick()) {
				openRequested = true;
			}
			if (openRequested) {
				openRequested = false;
				open(mc);
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			rt.trackers.onJoin(mc);
			dev.sculkwire.client.ui.DevScreenshots.onJoin();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> rt.trackers.onDisconnect());

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
			if (!rt.listensTo("chat.message")) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			String full = Filters.stripFormatting(message.getString());
			String text = signed != null ? signed.signedContent() : full;
			Map<String, Value> p = new LinkedHashMap<>();
			p.put("msg.text", Value.str(text));
			p.put("msg.full", Value.str(full));
			p.put("msg.sender", Value.str(sender != null ? sender.name() : ""));
			p.put("msg.sender_uuid", Value.str(sender != null ? sender.id().toString() : ""));
			p.put("msg.kind", Value.enumOf("player"));
			p.put("msg.is_own", Value.bool(sender != null && mc.player != null && sender.id().equals(mc.player.getUUID())));
			rt.post("chat.message", p);
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !rt.listensTo("chat.message")) {
				return;
			}
			String text = Filters.stripFormatting(message.getString());
			Map<String, Value> p = new LinkedHashMap<>();
			p.put("msg.text", Value.str(text));
			p.put("msg.full", Value.str(text));
			p.put("msg.sender", Value.str(""));
			p.put("msg.sender_uuid", Value.str(""));
			p.put("msg.kind", Value.enumOf("system"));
			p.put("msg.is_own", Value.FALSE);
			rt.post("chat.message", p);
		});

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> rt.trackers.onEntityLoad(entity, true));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> rt.trackers.onEntityLoad(entity, false));
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> rt.trackers.onScreenInit(screen));

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> rt.post("client.started", Map.of()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			rt.post("client.stopping", Map.of());
			rt.dispatcher.flush(3_000);
			rt.dispatcher.close();
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> SculkwireCommands.register(dispatcher));
		Sculkwire.LOG.info("{} loaded: {} signals, {} events, {} notifier types", Sculkwire.NAME,
				rt.signals.specs().size(), rt.events.all().size(), rt.notifiers.all().size());
	}

	private static void registerDevShots() {
		for (int i = 0; i < 6; i++) {
			int tab = i;
			dev.sculkwire.client.ui.DevScreenshots.add("tab" + i, () -> dev.sculkwire.client.ui.SculkwireScreen.atTab(null, tab));
		}
		dev.sculkwire.client.ui.DevScreenshots.add("rule-condition", () -> {
			var cfg = ClientRuntime.get().config();
			var r = cfg.rule("low-health-afk") != null ? cfg.rule("low-health-afk").copy() : cfg.rules.get(0).copy();
			return new dev.sculkwire.client.ui.RuleEditScreen(null, r, false);
		});
		dev.sculkwire.client.ui.DevScreenshots.add("rule-event", () -> {
			var cfg = ClientRuntime.get().config();
			var r = cfg.rule("ev-chat") != null ? cfg.rule("ev-chat").copy() : cfg.rules.get(0).copy();
			return new dev.sculkwire.client.ui.RuleEditScreen(null, r, false);
		});
		dev.sculkwire.client.ui.DevScreenshots.add("notifier-ntfy", () -> new dev.sculkwire.client.ui.NotifierEditScreen(null,
				dev.sculkwire.core.model.NotifierConfig.of("", "Phone", "ntfy"), true));
		dev.sculkwire.client.ui.DevScreenshots.add("notifier-webhook", () -> new dev.sculkwire.client.ui.NotifierEditScreen(null,
				dev.sculkwire.core.util.Json.copy(ClientRuntime.get().config().notifiers.get(0), dev.sculkwire.core.model.NotifierConfig.class), false));
	}

	private static void open(Minecraft mc) {
		if (screenFactory != null) {
			mc.gui.setScreen(screenFactory.apply(mc.gui.screen()));
		} else {
			ClientRuntime.chat(ClientRuntime.prefix().append(Component.literal("The editor is not available; use /sw help")));
		}
	}
}
