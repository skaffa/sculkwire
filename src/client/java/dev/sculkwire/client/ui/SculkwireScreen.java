package dev.sculkwire.client.ui;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.core.config.ImportExport;
import dev.sculkwire.core.config.Presets;
import dev.sculkwire.core.dispatch.Dispatcher;
import dev.sculkwire.core.dispatch.History;
import dev.sculkwire.core.engine.CompiledRule;
import dev.sculkwire.core.engine.RuleEngine;
import dev.sculkwire.core.model.Condition;
import dev.sculkwire.core.model.MessageTemplate;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Operand;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Trigger;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.util.Json;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Sculkwire editor: tabs for events, notifiers, variables, the live inspector, history and settings. */
public final class SculkwireScreen extends FormScreen {
	enum Tab {
		EVENTS("Events"),
		NOTIFIERS("Notifiers"),
		VARIABLES("Variables"),
		HISTORY("History"),
		SETTINGS("Settings");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static Tab lastTab = Tab.EVENTS;
	private final ClientRuntime rt = ClientRuntime.get();
	private Tab tab = lastTab;
	private List<UserVariable> varDraft;
	private long lastHistoryCount = -1;

	public SculkwireScreen(Screen parent) {
		super(Component.literal("Sculkwire"), parent);
	}

	/** Opens a given tab (0 = Events … 5 = Settings). */
	public static SculkwireScreen atTab(Screen parent, int index) {
		SculkwireScreen s = new SculkwireScreen(parent);
		s.tab = Tab.values()[Math.max(0, Math.min(Tab.values().length - 1, index))];
		return s;
	}

	@Override
	protected int headerHeight() {
		return 50;
	}

	@Override
	protected void buildFixed() {
		int n = Tab.values().length;
		int tw = Math.min(80, (width - 20) / n);
		int x = (width - tw * n) / 2;
		for (Tab t : Tab.values()) {
			Button b = button(t.label, () -> switchTab(t));
			b.active = t != tab;
			fixed(b, x, 22, tw - 2);
			x += tw;
		}
		footer(button("Done", this::onClose));
	}

	private void switchTab(Tab t) {
		tab = t;
		lastTab = t;
		scroll = 0;
		varDraft = null;
		rebuildWidgets();
	}

	@Override
	public void tick() {
		super.tick();
		if (tab == Tab.HISTORY && rt.history.entries().size() != lastHistoryCount) {
			rebuild();
		}
	}

	@Override
	protected void buildRows() {
		switch (tab) {
			case EVENTS -> events();
			case NOTIFIERS -> notifiers();
			case VARIABLES -> variables();
			case HISTORY -> history();
			case SETTINGS -> settings();
		}
	}

	@Override
	protected void extractExtra(GuiGraphicsExtractor g, int mx, int my) {
		long mute = rt.config().settings.muteUntil;
		if (mute > System.currentTimeMillis()) {
			g.text(font, "Muted until " + new SimpleDateFormat("HH:mm").format(new Date(mute)), 6, 8, WARN);
		}
	}

	// ---- events ----

	private void events() {
		SculkwireConfig cfg = rt.config();
		if (!cfg.settings.privacyNoticeShown) {
			text(Component.literal("Heads-up: notifications can contain chat text, coordinates and names, and they leave your computer "
					+ "when you send them to ntfy, Discord or a webhook. Public ntfy.sh topics can be read by anyone who knows the name."),
					WARN, 0);
			row(null).add(button("Got it", () -> {
				cfg.settings.privacyNoticeShown = true;
				rt.apply(cfg);
				rebuild();
			}), 70);
			space(4);
		}
		Row actions = row(null);
		actions.add(button("+ New", "Create an event from scratch", () -> {
			Rule r = new Rule();
			r.name = "";
			r.trigger = Trigger.event("chat.message");
			r.message = MessageTemplate.of("", "");
			if (cfg.notifiers.size() == 1) {
				r.targets.add(dev.sculkwire.core.model.Target.of(cfg.notifiers.get(0).id));
			}
			minecraft.gui.setScreen(new BlockEditorScreen(this, r, true));
		}), 60);
		actions.add(button("+ Preset", "Start from a ready-made event", this::pickPreset), 70);
		actions.add(button("Import", "Import events from JSON on the clipboard", () ->
				minecraft.gui.setScreen(new ImportScreen(this, minecraft.keyboardHandler.getClipboard()))), 60);
		actions.add(button("Export", "Copy all events as JSON to the clipboard", () -> {
			minecraft.keyboardHandler.setClipboard(ImportExport.export(cfg.rules, cfg.variables));
			ClientRuntime.info("Copied " + cfg.rules.size() + " event(s) to the clipboard (no notifier settings or secrets).");
		}), 60);
		if (cfg.rules.isEmpty()) {
			space(6);
			text("No events yet. Use + Preset for a quick start, e.g. 'Mentioned in chat' or 'Low health'.", GRAY);
			return;
		}
		for (Rule r : cfg.rules) {
			CompiledRule cr = rt.engine.rule(r.id);
			Row row = row(r.name.isBlank() ? r.id : r.name);
			row.dot(() -> !r.enabled ? 0xFF555555 : cr != null && !cr.errors.isEmpty() ? BAD
					: cr != null && !cr.warnings.isEmpty() ? WARN : GOOD);
			row.add(toggle(() -> r.enabled, v -> {
				r.enabled = v;
				rt.apply(cfg);
			}, "On", "Off"), 34);
			row.add(button("Edit", () -> minecraft.gui.setScreen(new BlockEditorScreen(this, r.copy(), false))), 36);
			row.add(button("Test", "Send now with current or sample values", () -> {
				if (rt.engine.testFire(r.id)) {
					ClientRuntime.info("Test of '" + r.name + "' sent.");
				} else {
					ClientRuntime.warn("'" + r.name + "' has no notifiers to send to.");
				}
			}), 36);
			row.add(button("⧉", "Duplicate", () -> {
				Rule copy = r.copy();
				copy.name = r.name + " (copy)";
				copy.id = cfg.uniqueId(copy.name);
				cfg.rules.add(cfg.rules.indexOf(r) + 1, copy);
				rt.apply(cfg);
				rebuild();
			}), 20);
			row.add(button("✖", "Delete", () -> confirm("Delete event '" + r.name + "'?", () -> {
				cfg.rules.remove(r);
				rt.apply(cfg);
			})), 20);
			live(null, () -> List.of(summary(r, cr)), DARK).indent(10);
		}
	}

	private Component summary(Rule r, CompiledRule cr) {
		String when = switch (r.trigger.kind) {
			case Trigger.CONDITION -> "when " + (r.trigger.condition == null ? "?" : r.trigger.condition.describe());
			case Trigger.CHANGE -> "when " + r.trigger.signal + " changes";
			case Trigger.SCHEDULE -> r.trigger.everySeconds > 0 ? "every " + Value.formatNumber(r.trigger.everySeconds) + " s"
					: r.trigger.atRealTime != null ? "at " + r.trigger.atRealTime : "at world time " + r.trigger.atWorldTime;
			default -> "on " + r.trigger.event;
		};
		List<String> targets = new ArrayList<>();
		for (String id : r.notifierIds()) {
			NotifierConfig n = rt.config().notifier(id);
			targets.add(n == null ? id + "?" : n.displayName());
		}
		if (r.hasBranches() && r.branches.size() > 1) {
			when += " · " + r.branches.size() + " blocks";
		}
		RuleEngine.Status st = rt.engine.status(r.id);
		String tail = cr != null && !cr.errors.isEmpty() ? "  ✖ " + cr.errors.get(0)
				: st.outcome().isEmpty() ? "" : "  · last: " + st.outcome();
		return Component.literal(when + " → " + (targets.isEmpty() ? "nobody" : String.join(", ", targets)) + tail);
	}

	private void pickPreset() {
		List<PickerScreen.Item<Presets.Preset>> items = new ArrayList<>();
		for (Presets.Preset p : Presets.builtin()) {
			items.add(new PickerScreen.Item<>(p.name(), p.description(), "", p));
		}
		minecraft.gui.setScreen(new PickerScreen<>(this, "Add a preset", items, p -> {
			SculkwireConfig cfg = rt.config();
			List<String> targets = new ArrayList<>();
			if (cfg.notifiers.size() == 1) {
				targets.add(cfg.notifiers.get(0).id);
			}
			Rule tmp = Presets.instantiate(p, new SculkwireConfig(), targets);
			for (UserVariable v : p.variables()) {
				if (cfg.variable(v.name) == null) {
					cfg.variables.add(Json.copy(v, UserVariable.class));
					rt.apply(cfg);
				}
			}
			minecraft.gui.setScreen(new BlockEditorScreen(this, tmp, true));
		}));
	}

	// ---- notifiers ----

	private void notifiers() {
		SculkwireConfig cfg = rt.config();
		row(null).add(button("+ Add notifier", this::pickNotifierType), 110);
		if (cfg.notifiers.isEmpty()) {
			space(6);
			text("No notifiers yet. ntfy is the easiest way to get pushes on your phone: install the ntfy app, "
					+ "subscribe to a topic, and add an ntfy notifier with the same topic.", GRAY);
			return;
		}
		for (NotifierConfig n : cfg.notifiers) {
			NotifierType type = rt.notifiers.get(n.type);
			Row row = row(n.displayName());
			row.dot(() -> {
				Dispatcher.Stats s = rt.dispatcher.stats(n.id);
				List<String> problems = type == null ? List.of("?") : type.validate(n, rt.secrets);
				return !n.enabled ? 0xFF555555 : !problems.isEmpty() || s.consecutiveFailures > 0 ? BAD
						: s.sent > 0 ? GOOD : ACCENT;
			});
			row.add(toggle(() -> n.enabled, v -> {
				n.enabled = v;
				rt.apply(cfg);
			}, "On", "Off"), 34);
			row.add(button("Edit", () -> minecraft.gui.setScreen(new NotifierEditScreen(this, Json.copy(n, NotifierConfig.class), false))), 36);
			row.add(button("Test", "Send a test notification", () -> {
				ClientRuntime.info("Sending a test to " + n.displayName() + "…");
				rt.sendTest(n.id).thenAccept(e -> minecraft.execute(() -> {
					if (e.status() == History.Status.SENT) {
						ClientRuntime.info("Test to " + n.displayName() + " sent.");
					} else {
						ClientRuntime.warn("Test to " + n.displayName() + " failed: " + e.error());
					}
				}));
			}), 36);
			row.add(button("✖", "Delete", () -> confirm("Delete notifier '" + n.displayName() + "'?", () -> {
				cfg.notifiers.remove(n);
				rt.secrets.removeNotifier(n.id);
				rt.saveSecrets();
				rt.apply(cfg);
			})), 20);
			live(null, () -> {
				Dispatcher.Stats s = rt.dispatcher.stats(n.id);
				List<String> problems = type == null ? List.of("unknown type") : type.validate(n, rt.secrets);
				String info = (type == null ? n.type : type.displayName())
						+ (!problems.isEmpty() ? "  ✖ " + problems.get(0)
						: s.consecutiveFailures > 0 ? "  ✖ " + s.lastError
						: s.sent > 0 ? "  · sent " + s.sent + (s.failed > 0 ? ", failed " + s.failed : "") : "");
				return List.of(Component.literal(info));
			}, DARK).indent(10);
		}
	}

	private void pickNotifierType() {
		List<PickerScreen.Item<NotifierType>> items = new ArrayList<>();
		for (NotifierType t : rt.notifiers.available()) {
			items.add(new PickerScreen.Item<>(t.displayName(), t.description(), "", t));
		}
		minecraft.gui.setScreen(new PickerScreen<>(this, "Add a notifier", items, t ->
				minecraft.gui.setScreen(new NotifierEditScreen(this, NotifierConfig.of("", t.displayName(), t.id()), true))));
	}

	// ---- variables ----

	private void variables() {
		if (varDraft == null) {
			varDraft = new ArrayList<>();
			for (UserVariable v : rt.config().variables) {
				varDraft.add(Json.copy(v, UserVariable.class));
			}
		}
		text("Use variables in messages and conditions as {name}. Lists can be used as 'one of' values, e.g. "
				+ "chat 'Only from: {friends}'. /sw var set|add|remove also changes them.", GRAY);
		space(4);
		List<String> types = List.of("STRING", "NUMBER", "BOOL", "LIST");
		for (UserVariable v : varDraft) {
			Row r = row(null);
			r.add(box(v.name, 40, s -> v.name = s.trim(), "name"), 100);
			r.add(cycle(types, () -> types.contains(v.type) ? v.type : "STRING", t -> v.type = t, t -> t.toLowerCase(Locale.ROOT)), 60);
			r.add(box(v.toValue().asString(), 2000, s -> v.value = Value.str(s).coerce(ValueType.parse(v.type)).toJson(),
					"value (lists: a, b, c)"), -1);
			r.add(button("✖", () -> {
				varDraft.remove(v);
				rebuild();
			}), 20);
		}
		Row actions = row(null);
		actions.add(button("+ Add", () -> {
			UserVariable u = new UserVariable();
			u.name = "var" + (varDraft.size() + 1);
			u.type = "STRING";
			varDraft.add(u);
			rebuild();
		}), 60);
		actions.add(button("Save variables", () -> {
			SculkwireConfig cfg = rt.config();
			List<UserVariable> clean = new ArrayList<>();
			for (UserVariable v : varDraft) {
				if (!v.name.isBlank() && v.name.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
					v.value = Value.fromJson(v.value).coerce(ValueType.parse(v.type)).toJson();
					clean.add(v);
				}
			}
			cfg.variables = clean;
			rt.apply(cfg);
			varDraft = null;
			ClientRuntime.info("Saved " + clean.size() + " variable(s).");
			rebuild();
		}), 110);
	}

	// ---- history ----

	private void history() {
		List<History.Entry> list = rt.history.entries();
		lastHistoryCount = list.size();
		row(null).add(button("Clear history", () -> confirm("Clear the delivery history?", rt.history::clear)), 100);
		if (list.isEmpty()) {
			text("Nothing sent yet.", GRAY);
			return;
		}
		SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss");
		for (History.Entry e : list) {
			Row r = row(fmt.format(new Date(e.time())));
			r.dot(() -> e.status() == History.Status.SENT ? GOOD : BAD);
			String who = (e.ruleName().isEmpty() ? "test" : e.ruleName()) + (e.test() ? " (test)" : "") + " → " + e.notifierName();
			r.live = () -> {
				List<Component> out = new ArrayList<>();
				out.add(Component.literal(who + ": " + e.title()).withStyle(ChatFormatting.WHITE));
				if (!e.error().isEmpty()) {
					out.add(Component.literal(e.status().name().toLowerCase(Locale.ROOT) + ": " + e.error()).withStyle(ChatFormatting.RED));
				}
				return out;
			};
		}
	}

	// ---- settings ----

	private void settings() {
		SculkwireConfig cfg = rt.config();
		var s = cfg.settings;
		header("Mute");
		Row mute = row("All notifications");
		mute.add(button("30 min", () -> mute(30)), 50).add(button("2 h", () -> mute(120)), 40).add(button("Unmute", () -> mute(0)), 56);
		mute.live = () -> List.of(Component.literal(s.muteUntil > System.currentTimeMillis()
				? "muted until " + new SimpleDateFormat("HH:mm").format(new Date(s.muteUntil)) : "on"));
		header("Quiet hours");
		row("Enabled").add(toggle(() -> s.quietHours.enabled, v -> {
			s.quietHours.enabled = v;
			rt.apply(cfg);
		}, "Yes", "No"), 60);
		row("From").add(box(s.quietHours.start, 5, v -> {
			s.quietHours.start = v.trim();
			rt.apply(cfg);
		}, "23:00"), 60);
		row("Until").add(box(s.quietHours.end, 5, v -> {
			s.quietHours.end = v.trim();
			rt.apply(cfg);
		}, "07:00"), 60);
		text("Events with 'Ignore quiet hours' still send.", DARK);
		header("Behaviour");
		row("Only when alt-tabbed").add(toggle(() -> s.defaultOnlyWhenUnfocused, v -> {
			s.defaultOnlyWhenUnfocused = v;
			rt.apply(cfg);
		}, "Yes", "No"), 60).live = () -> List.of(Component.literal("default for events (skip while you look at the game)"));
		row("Join grace (s)").add(box(Value.formatNumber(s.joinGraceSeconds), 5, v -> {
			s.joinGraceSeconds = Math.max(0, parseNumber(v, 2));
			rt.apply(cfg);
		}, "2"), 60).live = () -> List.of(Component.literal("conditions already true when you join do not fire"));
		row("History size").add(box(String.valueOf(s.historySize), 5, v -> {
			s.historySize = (int) Math.max(10, Math.min(5000, parseNumber(v, 200)));
			rt.apply(cfg);
		}, "200"), 60);
		header("Files");
		String path = rt.dir.toAbsolutePath().toString();
		row("Config folder").add(button("Copy path", () -> {
			minecraft.keyboardHandler.setClipboard(path);
			ClientRuntime.info("Copied " + path);
		}), 70).add(button("Reload", "Re-read config.json and secrets.json from disk", () -> {
			rt.reload().forEach(ClientRuntime::warn);
			rebuild();
		}), 56);
		text(Component.literal(path), DARK, 0);
		text(Component.literal("config.json holds events and notifiers (no secrets); secrets.json holds webhook URLs and "
				+ "tokens as plain text."), DARK, 0);
	}

	private void mute(int minutes) {
		SculkwireConfig cfg = rt.config();
		cfg.settings.muteUntil = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
		rt.apply(cfg);
	}

	private void confirm(String question, Runnable yes) {
		minecraft.gui.setScreen(new ConfirmScreen(ok -> {
			if (ok) {
				yes.run();
			}
			minecraft.gui.setScreen(this);
		}, Component.literal(question), Component.literal("This cannot be undone.")));
	}
}
