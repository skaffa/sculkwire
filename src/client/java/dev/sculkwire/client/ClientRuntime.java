package dev.sculkwire.client;

import dev.sculkwire.Sculkwire;
import dev.sculkwire.api.SculkwirePlugin;
import dev.sculkwire.client.notify.InGameNotifierType;
import dev.sculkwire.client.signals.ClientSignals;
import dev.sculkwire.client.tracking.Trackers;
import dev.sculkwire.core.config.ConfigStore;
import dev.sculkwire.core.config.Secrets;
import dev.sculkwire.core.dispatch.Dispatcher;
import dev.sculkwire.core.dispatch.History;
import dev.sculkwire.core.engine.RuleEngine;
import dev.sculkwire.core.event.BuiltinEvents;
import dev.sculkwire.core.event.EventRegistry;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.notify.JdkHttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.NotifierRegistry;
import dev.sculkwire.core.signal.BuiltinSignals;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.util.Clock;
import dev.sculkwire.core.value.Value;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Holds the mod's state on the client: registries, config, rule engine, dispatcher and trackers. */
public final class ClientRuntime {
	private static ClientRuntime instance;

	public final SignalRegistry signals = new SignalRegistry();
	public final EventRegistry events = new EventRegistry();
	public final NotifierRegistry notifiers = NotifierRegistry.withBuiltins();
	public final Clock clock = Clock.system();
	public final Path dir;
	public final ConfigStore store;
	public final Secrets secrets;
	public final History history;
	public final Dispatcher dispatcher;
	public final RuleEngine engine;
	public final Trackers trackers;
	private SculkwireConfig config;
	private final List<String> pendingWarnings = new ArrayList<>();
	private boolean debugEvents;

	private ClientRuntime() {
		dir = FabricLoader.getInstance().getConfigDir().resolve(Sculkwire.MOD_ID);
		store = new ConfigStore(dir);
		secrets = new Secrets(dir.resolve("secrets.json"));
		String secretWarning = secrets.load();
		if (secretWarning != null) {
			pendingWarnings.add(secretWarning);
		}
		ConfigStore.Loaded loaded = store.load();
		config = loaded.config();
		pendingWarnings.addAll(loaded.warnings());

		BuiltinSignals.defineAll(signals);
		BuiltinSignals.registerCoreSources(signals, clock);
		BuiltinEvents.defineAll(events);
		notifiers.register(new InGameNotifierType());
		trackers = new Trackers(this);
		ClientSignals.register(signals, trackers);
		runPlugins();

		history = new History(dir.resolve("history.jsonl"), config.settings.historySize);
		history.load();
		dispatcher = new Dispatcher(notifiers, () -> config, secrets, secrets::allValues, new JdkHttpTransport(), history, clock);
		dispatcher.setFailureWarning(msg -> Minecraft.getInstance().execute(() ->
				warn("A notifier keeps failing: " + msg)));
		engine = new RuleEngine(signals, events, clock, d -> dispatcher.submit(d.notifierId(), d.notification()));
		engine.setNotifierTemplates(dispatcher::templatesFor);
		engine.setTagResolver(new Tags());
		engine.load(config);
		for (var cr : engine.rules()) {
			if (!cr.errors.isEmpty()) {
				pendingWarnings.add("Event '" + cr.rule.name + "' has problems and is inactive: " + String.join("; ", cr.errors));
			}
		}
	}

	public static ClientRuntime init() {
		if (instance == null) {
			instance = new ClientRuntime();
		}
		return instance;
	}

	public static ClientRuntime get() {
		return instance;
	}

	private void runPlugins() {
		for (EntrypointContainer<SculkwirePlugin> c : FabricLoader.getInstance().getEntrypointContainers("sculkwire", SculkwirePlugin.class)) {
			try {
				SculkwirePlugin p = c.getEntrypoint();
				p.registerSignals(signals);
				p.registerEvents(events);
				p.registerNotifierTypes(notifiers);
			} catch (RuntimeException | LinkageError e) {
				Sculkwire.LOG.warn("Sculkwire plugin from {} failed", c.getProvider().getMetadata().getId(), e);
			}
		}
	}

	public SculkwireConfig config() {
		return config;
	}

	/** Applies an edited config: recompiles rules, drops cached notifiers, saves to disk. */
	public void apply(SculkwireConfig cfg) {
		config = cfg.sanitize();
		dispatcher.invalidate();
		history.setMax(config.settings.historySize);
		engine.load(config);
		save();
	}

	public void save() {
		try {
			store.save(config);
		} catch (IOException e) {
			warn("Could not save config: " + e.getMessage());
		}
	}

	public void saveSecrets() {
		try {
			secrets.save();
		} catch (IOException e) {
			warn("Could not save secrets: " + e.getMessage());
		}
		dispatcher.invalidate();
		engine.load(config);
	}

	/** Re-reads config.json and secrets.json from disk. */
	public List<String> reload() {
		List<String> warnings = new ArrayList<>();
		String sw = secrets.load();
		if (sw != null) {
			warnings.add(sw);
		}
		ConfigStore.Loaded loaded = store.load();
		warnings.addAll(loaded.warnings());
		config = loaded.config();
		dispatcher.invalidate();
		engine.load(config);
		return warnings;
	}

	public CompletableFuture<History.Entry> sendTest(String notifierId) {
		return dispatcher.submit(notifierId, Notification.simple("Sculkwire test",
				"If you can read this, the notifier works.", dev.sculkwire.core.notify.Priority.DEFAULT, true));
	}

	public void post(String eventId, Map<String, Value> payload) {
		engine.post(eventId, payload);
	}

	public boolean listensTo(String eventId) {
		return engine.listensTo(eventId) || debugEvents;
	}

	public void setDebugEvents(boolean on) {
		debugEvents = on;
		engine.setDebug(on ? line -> Minecraft.getInstance().execute(() -> chat(Component.literal(line).withStyle(ChatFormatting.DARK_GRAY))) : null);
	}

	public boolean debugEvents() {
		return debugEvents;
	}

	/** Shows warnings collected during startup once the player is in a world. */
	public void flushWarnings() {
		if (pendingWarnings.isEmpty() || Minecraft.getInstance().player == null) {
			return;
		}
		for (String w : pendingWarnings) {
			warn(w);
		}
		pendingWarnings.clear();
	}

	// ---- chat output ----

	public static MutableComponent prefix() {
		return Component.literal("[Sculkwire] ").withStyle(ChatFormatting.DARK_AQUA);
	}

	public static void chat(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null && mc.gui.hud != null) {
			mc.gui.hud.getChat().addClientSystemMessage(c);
		}
	}

	public static void info(String text) {
		chat(prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
	}

	public static void warn(String text) {
		chat(prefix().append(Component.literal(text).withStyle(ChatFormatting.GOLD)));
	}
}
