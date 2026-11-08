package dev.sculkwire.client.command;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.client.SculkwireClient;
import dev.sculkwire.core.config.Presets;
import dev.sculkwire.core.dispatch.Dispatcher;
import dev.sculkwire.core.dispatch.History;
import dev.sculkwire.core.engine.CompiledRule;
import dev.sculkwire.core.engine.RuleEngine;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.signal.SignalRef;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.util.Redact;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /sculkwire} (alias {@code /sw}). Everything is local; nothing is sent to the server. */
public final class SculkwireCommands {
	private SculkwireCommands() {
	}

	private static ClientRuntime rt() {
		return ClientRuntime.get();
	}

	private static SuggestionProvider<FabricClientCommandSource> ids(Supplier<List<String>> s) {
		return (ctx, b) -> SharedSuggestionProvider.suggest(s.get(), b);
	}

	private static final SuggestionProvider<FabricClientCommandSource> RULES = ids(() ->
			rt().config().rules.stream().map(r -> r.id).toList());
	private static final SuggestionProvider<FabricClientCommandSource> NOTIFIERS = ids(() ->
			rt().config().notifiers.stream().map(n -> n.id).toList());
	private static final SuggestionProvider<FabricClientCommandSource> SIGNALS = ids(() ->
			rt().signals.specs().stream().map(SignalSpec::id).toList());
	private static final SuggestionProvider<FabricClientCommandSource> VARS = ids(() ->
			rt().config().variables.stream().map(v -> v.name).toList());
	private static final SuggestionProvider<FabricClientCommandSource> PRESETS = ids(() ->
			Presets.builtin().stream().map(Presets.Preset::id).toList());
	private static final SuggestionProvider<FabricClientCommandSource> TYPES = ids(() ->
			rt().notifiers.available().stream().map(NotifierType::id).toList());

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(build("sculkwire"));
		d.register(build("sw"));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
		return literal(name)
				.executes(c -> {
					SculkwireClient.openScreenNextTick();
					return 1;
				})
				.then(literal("help").executes(SculkwireCommands::help))
				.then(literal("test").then(argument("notifier", StringArgumentType.word()).suggests(NOTIFIERS)
						.executes(c -> test(c, StringArgumentType.getString(c, "notifier")))))
				.then(literal("fire").then(argument("rule", StringArgumentType.word()).suggests(RULES)
						.executes(c -> fire(c, StringArgumentType.getString(c, "rule")))))
				.then(literal("list")
						.executes(c -> listRules(c))
						.then(literal("rules").executes(SculkwireCommands::listRules))
						.then(literal("notifiers").executes(SculkwireCommands::listNotifiers))
						.then(literal("presets").executes(SculkwireCommands::listPresets))
						.then(literal("variables").executes(SculkwireCommands::listVars)))
				.then(literal("status").then(argument("rule", StringArgumentType.word()).suggests(RULES)
						.executes(c -> status(c, StringArgumentType.getString(c, "rule")))))
				.then(literal("enable").then(argument("rule", StringArgumentType.word()).suggests(RULES)
						.executes(c -> setEnabled(c, StringArgumentType.getString(c, "rule"), true))))
				.then(literal("disable").then(argument("rule", StringArgumentType.word()).suggests(RULES)
						.executes(c -> setEnabled(c, StringArgumentType.getString(c, "rule"), false))))
				.then(literal("mute")
						.executes(c -> mute(c, 30))
						.then(argument("minutes", IntegerArgumentType.integer(1, 7 * 24 * 60))
								.executes(c -> mute(c, IntegerArgumentType.getInteger(c, "minutes")))))
				.then(literal("unmute").executes(c -> mute(c, 0)))
				.then(literal("reload").executes(SculkwireCommands::reload))
				.then(literal("signal").then(argument("signal", StringArgumentType.greedyString()).suggests(SIGNALS)
						.executes(c -> signal(c, StringArgumentType.getString(c, "signal")))))
				.then(literal("signals").executes(c -> signals(c, ""))
						.then(argument("filter", StringArgumentType.word()).executes(c -> signals(c, StringArgumentType.getString(c, "filter")))))
				.then(literal("var")
						.then(literal("set").then(argument("name", StringArgumentType.word()).suggests(VARS)
								.then(argument("value", StringArgumentType.greedyString())
										.executes(c -> varSet(c, StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "value"))))))
						.then(literal("add").then(argument("name", StringArgumentType.word()).suggests(VARS)
								.then(argument("value", StringArgumentType.greedyString())
										.executes(c -> varList(c, StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "value"), true)))))
						.then(literal("remove").then(argument("name", StringArgumentType.word()).suggests(VARS)
								.then(argument("value", StringArgumentType.greedyString())
										.executes(c -> varList(c, StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "value"), false)))))
						.then(literal("clear").then(argument("name", StringArgumentType.word()).suggests(VARS)
								.executes(c -> varClear(c, StringArgumentType.getString(c, "name"))))))
				.then(literal("emit").then(argument("name", StringArgumentType.word())
						.executes(c -> emit(c, StringArgumentType.getString(c, "name"), ""))
						.then(argument("text", StringArgumentType.greedyString())
								.executes(c -> emit(c, StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "text"))))))
				.then(literal("preset").then(argument("preset", StringArgumentType.word()).suggests(PRESETS)
						.executes(c -> preset(c, StringArgumentType.getString(c, "preset"), ""))
						.then(argument("notifiers", StringArgumentType.greedyString()).suggests(NOTIFIERS)
								.executes(c -> preset(c, StringArgumentType.getString(c, "preset"), StringArgumentType.getString(c, "notifiers"))))))
				.then(literal("notifier")
						.then(literal("add").then(argument("type", StringArgumentType.word()).suggests(TYPES)
								.then(argument("id", StringArgumentType.word())
										.executes(c -> notifierAdd(c, StringArgumentType.getString(c, "type"), StringArgumentType.getString(c, "id"))))))
						.then(literal("set").then(argument("id", StringArgumentType.word()).suggests(NOTIFIERS)
								.then(argument("key", StringArgumentType.word()).suggests((c, b) -> {
									NotifierConfig n = rt().config().notifier(StringArgumentType.getString(c, "id"));
									NotifierType t = n == null ? null : rt().notifiers.get(n.type);
									return SharedSuggestionProvider.suggest(t == null ? List.of() : t.fields().stream().map(FieldSpec::key).toList(), b);
								}).then(argument("value", StringArgumentType.greedyString())
										.executes(c -> notifierSet(c, StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "key"),
												StringArgumentType.getString(c, "value")))))))
						.then(literal("remove").then(argument("id", StringArgumentType.word()).suggests(NOTIFIERS)
								.executes(c -> notifierRemove(c, StringArgumentType.getString(c, "id"))))))
				.then(literal("history").executes(c -> history(c, 10))
						.then(argument("count", IntegerArgumentType.integer(1, 50)).executes(c -> history(c, IntegerArgumentType.getInteger(c, "count")))))
				.then(literal("debug")
						.then(literal("events").executes(SculkwireCommands::debugEvents))
						.then(literal("perf").executes(SculkwireCommands::perf)));
	}

	// ---- output helpers ----

	private static void out(CommandContext<FabricClientCommandSource> c, Component msg) {
		c.getSource().sendFeedback(ClientRuntime.prefix().append(msg));
	}

	private static void out(CommandContext<FabricClientCommandSource> c, String msg) {
		out(c, Component.literal(msg).withStyle(ChatFormatting.GRAY));
	}

	private static int err(CommandContext<FabricClientCommandSource> c, String msg) {
		c.getSource().sendError(ClientRuntime.prefix().append(Component.literal(msg)));
		return 0;
	}

	private static MutableComponent suggestLink(String label, String command) {
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.SuggestCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	// ---- commands ----

	private static int help(CommandContext<FabricClientCommandSource> c) {
		String[] lines = {
				"/sw - open the editor",
				"/sw list [rules|notifiers|presets|variables]",
				"/sw test <notifier> - send a test notification",
				"/sw fire <event> - send an event's notification now",
				"/sw status <event> - problems and last outcome",
				"/sw enable|disable <event>",
				"/sw mute [minutes] / unmute",
				"/sw signal <id> [k=v] - show a live value; /sw signals [filter]",
				"/sw var set|add|remove|clear <name> [value]",
				"/sw preset <id> [notifier ...] - add a preset event",
				"/sw notifier add <type> <id> / set <id> <key> <value> / remove <id>",
				"/sw emit <name> [text] - post a custom event",
				"/sw history [n], /sw reload, /sw debug events|perf"};
		out(c, "Commands:");
		for (String l : lines) {
			c.getSource().sendFeedback(Component.literal("  " + l).withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	private static int test(CommandContext<FabricClientCommandSource> c, String id) {
		NotifierConfig n = rt().config().notifier(id);
		if (n == null) {
			return err(c, "No notifier '" + id + "'");
		}
		NotifierType t = rt().notifiers.get(n.type);
		if (t != null) {
			List<String> problems = t.validate(n, rt().secrets);
			if (!problems.isEmpty()) {
				return err(c, n.displayName() + ": " + String.join("; ", problems));
			}
		}
		out(c, "Sending a test to " + n.displayName() + "…");
		rt().sendTest(id).thenAccept(e -> Minecraft.getInstance().execute(() -> {
			if (e.status() == History.Status.SENT) {
				ClientRuntime.info("Test to " + n.displayName() + " sent (" + (e.httpStatus() > 0 ? "HTTP " + e.httpStatus() + ", " : "") + e.latencyMs() + " ms)");
			} else {
				ClientRuntime.warn("Test to " + n.displayName() + " failed: " + e.error());
			}
		}));
		return 1;
	}

	private static int fire(CommandContext<FabricClientCommandSource> c, String id) {
		CompiledRule cr = rt().engine.rule(id);
		if (cr == null) {
			return err(c, "No event '" + id + "'");
		}
		if (!rt().engine.testFire(id)) {
			return err(c, "'" + cr.rule.name + "' has no notifiers to send to");
		}
		out(c, "Sent '" + cr.rule.name + "' (marked as test)");
		return 1;
	}

	private static int listRules(CommandContext<FabricClientCommandSource> c) {
		List<CompiledRule> rules = rt().engine.rules();
		if (rules.isEmpty()) {
			out(c, Component.literal("No events yet. Try ").withStyle(ChatFormatting.GRAY)
					.append(suggestLink("/sw preset mentioned", "/sw preset mentioned "))
					.append(Component.literal(" or ").withStyle(ChatFormatting.GRAY))
					.append(suggestLink("/sw list presets", "/sw list presets")));
			return 1;
		}
		out(c, rules.size() + " event(s):");
		for (CompiledRule cr : rules) {
			ChatFormatting color = !cr.rule.enabled ? ChatFormatting.DARK_GRAY : cr.errors.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
			String targets = cr.rule.targets.stream().map(t -> t.notifier).reduce((a, b) -> a + ", " + b).orElse("nobody");
			RuleEngine.Status st = rt().engine.status(cr.rule.id);
			c.getSource().sendFeedback(Component.literal("  ● ").withStyle(color)
					.append(suggestLink(cr.rule.id, "/sw status " + cr.rule.id))
					.append(Component.literal(" " + cr.rule.name + " → " + targets
							+ (st.outcome().isEmpty() ? "" : " (last: " + st.outcome() + ")")).withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int listNotifiers(CommandContext<FabricClientCommandSource> c) {
		List<NotifierConfig> list = rt().config().notifiers;
		if (list.isEmpty()) {
			out(c, Component.literal("No notifiers yet. Example: ").withStyle(ChatFormatting.GRAY)
					.append(suggestLink("/sw notifier add ntfy phone", "/sw notifier add ntfy phone")));
			return 1;
		}
		out(c, list.size() + " notifier(s):");
		for (NotifierConfig n : list) {
			Dispatcher.Stats s = rt().dispatcher.stats(n.id);
			NotifierType t = rt().notifiers.get(n.type);
			List<String> problems = t == null ? List.of("unknown type") : t.validate(n, rt().secrets);
			ChatFormatting color = !n.enabled ? ChatFormatting.DARK_GRAY : !problems.isEmpty() || s.consecutiveFailures > 0 ? ChatFormatting.RED : ChatFormatting.GREEN;
			String extra = !problems.isEmpty() ? " – " + String.join("; ", problems)
					: s.sent + s.failed > 0 ? " – sent " + s.sent + ", failed " + s.failed : "";
			c.getSource().sendFeedback(Component.literal("  ● ").withStyle(color)
					.append(suggestLink(n.id, "/sw test " + n.id))
					.append(Component.literal(" (" + n.type + ") " + n.displayName() + extra).withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int listPresets(CommandContext<FabricClientCommandSource> c) {
		out(c, "Presets (click to add):");
		for (Presets.Preset p : Presets.builtin()) {
			c.getSource().sendFeedback(Component.literal("  ").append(suggestLink(p.id(), "/sw preset " + p.id() + " "))
					.append(Component.literal(" " + p.description()).withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int listVars(CommandContext<FabricClientCommandSource> c) {
		Map<String, Value> vars = rt().engine.variables();
		if (vars.isEmpty()) {
			out(c, "No variables. Example: /sw var add friends Alex");
			return 1;
		}
		vars.forEach((k, v) -> c.getSource().sendFeedback(Component.literal("  " + k + " (" + v.type().id() + ") = " + v.asString()).withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> c, String id) {
		CompiledRule cr = rt().engine.rule(id);
		if (cr == null) {
			return err(c, "No event '" + id + "'");
		}
		out(c, cr.rule.name + (cr.rule.enabled ? "" : " (disabled)") + " – trigger: " + cr.rule.trigger.kind
				+ (cr.event != null ? " " + cr.event.id() : ""));
		for (String e : cr.errors) {
			c.getSource().sendFeedback(Component.literal("  ✖ " + e).withStyle(ChatFormatting.RED));
		}
		for (String w : cr.warnings) {
			c.getSource().sendFeedback(Component.literal("  ⚠ " + w).withStyle(ChatFormatting.GOLD));
		}
		RuleEngine.Status st = rt().engine.status(id);
		if (!st.outcome().isEmpty()) {
			c.getSource().sendFeedback(Component.literal("  last: " + st.outcome() + " at " + time(st.at())).withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	private static int setEnabled(CommandContext<FabricClientCommandSource> c, String id, boolean on) {
		SculkwireConfig cfg = rt().config();
		Rule r = cfg.rule(id);
		if (r == null) {
			return err(c, "No event '" + id + "'");
		}
		r.enabled = on;
		rt().apply(cfg);
		out(c, (on ? "Enabled '" : "Disabled '") + r.name + "'");
		return 1;
	}

	private static int mute(CommandContext<FabricClientCommandSource> c, int minutes) {
		SculkwireConfig cfg = rt().config();
		cfg.settings.muteUntil = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
		rt().apply(cfg);
		out(c, minutes <= 0 ? "Notifications unmuted" : "Muted for " + minutes + " minute(s)");
		return 1;
	}

	private static int reload(CommandContext<FabricClientCommandSource> c) {
		List<String> warnings = rt().reload();
		out(c, "Reloaded " + rt().config().rules.size() + " event(s) and " + rt().config().notifiers.size() + " notifier(s)");
		warnings.forEach(ClientRuntime::warn);
		for (CompiledRule cr : rt().engine.rules()) {
			if (!cr.errors.isEmpty()) {
				ClientRuntime.warn("'" + cr.rule.name + "': " + String.join("; ", cr.errors));
			}
		}
		return 1;
	}

	private static int signal(CommandContext<FabricClientCommandSource> c, String text) {
		String t = text.trim();
		Map<String, String> params = new LinkedHashMap<>();
		String id = t;
		if (!t.contains("(") && t.contains(" ")) {
			String[] parts = t.split("\\s+");
			id = parts[0];
			for (int i = 1; i < parts.length; i++) {
				int eq = parts[i].indexOf('=');
				if (eq > 0) {
					params.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
				}
			}
		} else {
			SignalRef ref = SignalRef.parse(t);
			id = ref.id();
			params.putAll(ref.params());
		}
		SignalSpec spec = rt().signals.spec(id);
		if (spec == null) {
			return err(c, "Unknown signal '" + id + "'. See /sw signals");
		}
		Value v = rt().engine.reader().read(id, params);
		out(c, Component.literal(id + " = ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(v.isNone() ? "(not available)" : v.asString()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  " + spec.type().id() + (spec.unit().isEmpty() ? "" : ", " + spec.unit())).withStyle(ChatFormatting.DARK_GRAY)));
		return 1;
	}

	private static int signals(CommandContext<FabricClientCommandSource> c, String filter) {
		List<String> ids = new ArrayList<>();
		for (SignalSpec s : rt().signals.specs()) {
			if (filter.isEmpty() || s.id().contains(filter)) {
				ids.add(s.id());
			}
		}
		out(c, ids.size() + " signal(s): " + String.join(", ", ids));
		return 1;
	}

	private static int varSet(CommandContext<FabricClientCommandSource> c, String name, String value) {
		String v = value.trim();
		Value val;
		if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
			val = Value.bool(Boolean.parseBoolean(v));
		} else if (v.matches("-?\\d+(\\.\\d+)?")) {
			val = Value.num(Double.parseDouble(v));
		} else {
			val = Value.str(v);
		}
		rt().engine.setVariable(name, val);
		rt().apply(rt().engine.config());
		out(c, name + " = " + val.asString() + " (" + val.type().id() + ")");
		return 1;
	}

	private static int varList(CommandContext<FabricClientCommandSource> c, String name, String item, boolean add) {
		Value cur = rt().engine.variables().getOrDefault(name, Value.list(List.of()));
		if (cur.type() != ValueType.LIST && !cur.isNone()) {
			return err(c, name + " is not a list");
		}
		List<String> items = new ArrayList<>(cur.asList());
		for (String part : Value.splitList(item)) {
			if (add) {
				if (items.stream().noneMatch(x -> x.equalsIgnoreCase(part))) {
					items.add(part);
				}
			} else {
				items.removeIf(x -> x.equalsIgnoreCase(part));
			}
		}
		rt().engine.setVariable(name, Value.list(items));
		rt().apply(rt().engine.config());
		out(c, name + " = [" + String.join(", ", items) + "]");
		return 1;
	}

	private static int varClear(CommandContext<FabricClientCommandSource> c, String name) {
		rt().engine.setVariable(name, Value.NONE);
		rt().apply(rt().engine.config());
		out(c, "Removed variable " + name);
		return 1;
	}

	private static int emit(CommandContext<FabricClientCommandSource> c, String name, String text) {
		rt().post("custom", Map.of("name", Value.str(name), "text", Value.str(text)));
		out(c, "Posted custom event '" + name + "'");
		return 1;
	}

	private static int preset(CommandContext<FabricClientCommandSource> c, String id, String notifiers) {
		Presets.Preset p = Presets.find(id);
		if (p == null) {
			return err(c, "No preset '" + id + "'. See /sw list presets");
		}
		SculkwireConfig cfg = rt().config();
		List<String> targets = new ArrayList<>();
		for (String n : notifiers.split("[\\s,]+")) {
			if (n.isBlank()) {
				continue;
			}
			if (cfg.notifier(n) == null) {
				return err(c, "No notifier '" + n + "'");
			}
			targets.add(n);
		}
		if (targets.isEmpty() && cfg.notifiers.size() == 1) {
			targets.add(cfg.notifiers.get(0).id);
		}
		Rule r = Presets.instantiate(p, cfg, targets);
		rt().apply(cfg);
		out(c, "Added event '" + r.name + "' (" + r.id + ")" + (targets.isEmpty() ? " – it has no notifiers yet" : " → " + String.join(", ", targets)));
		return 1;
	}

	private static int notifierAdd(CommandContext<FabricClientCommandSource> c, String type, String id) {
		NotifierType t = rt().notifiers.get(type);
		if (t == null) {
			return err(c, "Unknown type '" + type + "'");
		}
		SculkwireConfig cfg = rt().config();
		if (cfg.notifier(id) != null || cfg.rule(id) != null) {
			return err(c, "The id '" + id + "' is taken");
		}
		cfg.notifiers.add(NotifierConfig.of(id, id, type));
		rt().apply(cfg);
		List<String> keys = t.fields().stream().filter(FieldSpec::required).map(FieldSpec::key).toList();
		out(c, "Added " + t.displayName() + " notifier '" + id + "'" + (keys.isEmpty() ? "" : ". Now set: " + String.join(", ", keys)
				+ " with /sw notifier set " + id + " <key> <value>"));
		return 1;
	}

	private static int notifierSet(CommandContext<FabricClientCommandSource> c, String id, String key, String value) {
		SculkwireConfig cfg = rt().config();
		NotifierConfig n = cfg.notifier(id);
		if (n == null) {
			return err(c, "No notifier '" + id + "'");
		}
		NotifierType t = rt().notifiers.get(n.type);
		FieldSpec f = t == null ? null : t.field(key);
		if (f == null) {
			return err(c, "Unknown setting '" + key + "'");
		}
		String v = value.trim();
		if (f.secret()) {
			rt().secrets.set(id, key, v);
			rt().saveSecrets();
			out(c, "Saved " + f.label() + " for " + id + " (secret: " + Redact.mask(v) + ")");
			return 1;
		}
		switch (f.kind()) {
			case BOOL -> n.settings.addProperty(key, Boolean.parseBoolean(v));
			case INT, NUMBER -> {
				try {
					n.settings.addProperty(key, Double.parseDouble(v));
				} catch (NumberFormatException e) {
					return err(c, key + " needs a number");
				}
			}
			case STRING_LIST, ID_LIST -> {
				com.google.gson.JsonArray a = new com.google.gson.JsonArray();
				Value.splitList(v).forEach(a::add);
				n.settings.add(key, a);
			}
			default -> n.settings.add(key, new JsonPrimitive(v));
		}
		rt().apply(cfg);
		out(c, id + "." + key + " = " + v);
		return 1;
	}

	private static int notifierRemove(CommandContext<FabricClientCommandSource> c, String id) {
		SculkwireConfig cfg = rt().config();
		if (!cfg.notifiers.removeIf(n -> n.id.equals(id))) {
			return err(c, "No notifier '" + id + "'");
		}
		rt().secrets.removeNotifier(id);
		rt().saveSecrets();
		rt().apply(cfg);
		out(c, "Removed notifier '" + id + "' (events that used it now skip it)");
		return 1;
	}

	private static int history(CommandContext<FabricClientCommandSource> c, int count) {
		List<History.Entry> list = rt().history.entries();
		if (list.isEmpty()) {
			out(c, "Nothing sent yet");
			return 1;
		}
		out(c, "Last " + Math.min(count, list.size()) + " deliveries:");
		for (History.Entry e : list.subList(0, Math.min(count, list.size()))) {
			ChatFormatting color = e.status() == History.Status.SENT ? ChatFormatting.GREEN : ChatFormatting.RED;
			c.getSource().sendFeedback(Component.literal("  " + time(e.time()) + " ").withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(e.status().name().toLowerCase()).withStyle(color))
					.append(Component.literal(" " + (e.ruleName().isEmpty() ? "test" : e.ruleName()) + " → " + e.notifierName()
							+ (e.error().isEmpty() ? "" : ": " + e.error())).withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int debugEvents(CommandContext<FabricClientCommandSource> c) {
		rt().setDebugEvents(!rt().debugEvents());
		out(c, "Event debug output " + (rt().debugEvents() ? "on" : "off"));
		return 1;
	}

	private static int perf(CommandContext<FabricClientCommandSource> c) {
		double us = rt().trackers.engineMicros();
		out(c, String.format("Rule engine: %.1f µs per tick on average (%d events)", us, rt().engine.rules().size()));
		return 1;
	}

	private static String time(long ms) {
		return new SimpleDateFormat("HH:mm:ss").format(new Date(ms));
	}
}
