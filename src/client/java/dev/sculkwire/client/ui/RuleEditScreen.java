package dev.sculkwire.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.core.engine.CompiledRule;
import dev.sculkwire.core.engine.EvalContext;
import dev.sculkwire.core.engine.RuleEngine;
import dev.sculkwire.core.event.EventSpec;
import dev.sculkwire.core.event.PayloadVar;
import dev.sculkwire.core.model.Condition;
import dev.sculkwire.core.model.MessageTemplate;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Operand;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Target;
import dev.sculkwire.core.model.Trigger;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.signal.ParamSpec;
import dev.sculkwire.core.signal.SignalRef;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.template.Filters;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.value.Op;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Edits one event (rule): trigger, conditions, message, notifiers, limits and scope. */
public final class RuleEditScreen extends FormScreen {
	private static final List<String> KINDS = List.of(Trigger.EVENT, Trigger.CONDITION, Trigger.CHANGE, Trigger.SCHEDULE);
	private static final List<String> PRIORITIES = List.of("min", "low", "default", "high", "urgent");

	/** Which part to edit when used as a popup of the block editor (null = everything). */
	public enum Section {
		TRIGGER,
		SETTINGS,
		COMPARE,
		SEND
	}

	private final ClientRuntime rt = ClientRuntime.get();
	private final Section section;
	private Condition editCond;
	private dev.sculkwire.core.model.Send editSend;
	private MessageTemplate previewMessage;
	private final Rule rule;
	private final boolean isNew;
	private final String originalId;
	private CompiledRule compiled;
	private final List<EditBox> templateBoxes = new ArrayList<>();
	private EditBox lastTemplateBox;

	public RuleEditScreen(Screen parent, Rule rule, boolean isNew) {
		this(parent, rule, isNew, null, null);
	}

	/** A popup that edits one part of {@code rule} in place; Done returns to the parent. */
	public static RuleEditScreen popup(Screen parent, Rule rule, Section section, Object target) {
		return new RuleEditScreen(parent, rule, false, section, target);
	}

	private RuleEditScreen(Screen parent, Rule rule, boolean isNew, Section section, Object target) {
		super(Component.literal(section == null ? (isNew ? "New event" : "Edit event") : switch (section) {
			case TRIGGER -> "When…";
			case SETTINGS -> "Event settings";
			case COMPARE -> "Condition";
			case SEND -> "Send";
		}), parent);
		this.section = section;
		if (target instanceof Condition c) {
			editCond = c;
		} else if (target instanceof dev.sculkwire.core.model.Send s) {
			editSend = s;
			if (s.message.tags == null) {
				s.message.tags = new ArrayList<>();
			}
			if (s.message.priority == null) {
				s.message.priority = "default";
			}
		}
		this.rule = rule;
		this.isNew = isNew;
		this.originalId = rule.id;
		if (rule.trigger == null) {
			rule.trigger = new Trigger();
		}
		if (rule.message == null) {
			rule.message = MessageTemplate.of("", "");
		}
		if (rule.message.tags == null) {
			rule.message.tags = new ArrayList<>();
		}
		ensureTriggerDefaults();
		changed();
	}

	private void changed() {
		compiled = rt.engine.compiler().compile(rule, rt.config());
	}

	private void structural() {
		changed();
		rebuild();
	}

	private void ensureTriggerDefaults() {
		Trigger t = rule.trigger;
		switch (t.kind == null ? Trigger.EVENT : t.kind) {
			case Trigger.EVENT -> {
				if (t.params == null) {
					t.params = new JsonObject();
				}
			}
			case Trigger.CONDITION -> {
				if (t.condition == null) {
					t.condition = new Condition();
					t.condition.op = "lt";
				}
			}
			default -> {
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		for (EditBox b : templateBoxes) {
			if (b.isFocused()) {
				lastTemplateBox = b;
			}
		}
	}

	@Override
	protected void buildFixed() {
		if (section != null) {
			footer(button("Done", this::onClose));
			return;
		}
		footer(button("Save", this::save), button("Test send", "Send this event's message now (marked as test)", this::test),
				button("Cancel", this::onClose));
	}

	private void buildSection() {
		switch (section) {
			case TRIGGER -> {
				header("When");
				triggerRows();
				if (rule.conditions != null) {
					header("Only if");
					condRows(rule.conditions, 0, () -> {
						rule.conditions = null;
						structural();
					});
				}
			}
			case SETTINGS -> {
				header("General");
				row("Name").add(box(rule.name, 100, v -> {
					rule.name = v;
					changed();
				}, "e.g. Low health"), -1);
				row("Enabled").add(toggle(() -> rule.enabled, v -> rule.enabled = v, "Yes", "No"), 70);
				limitsSection();
				scopeSection();
			}
			case COMPARE -> {
				header("Check");
				condRows(editCond, 0, null);
				text(Component.literal("Values can be numbers, text, ids, a list (a, b, c) or a {variable}."), DARK, 0);
			}
			case SEND -> {
				messageSection(editSend.message);
				header("Send to");
				if (rt.config().notifiers.isEmpty()) {
					text("You have no notifiers yet. Add one on the Notifiers tab.", WARN);
				}
				for (NotifierConfig n : rt.config().notifiers) {
					row(n.displayName() + " (" + n.type + ")").add(toggle(() -> editSend.notifiers.contains(n.id), v -> {
						editSend.notifiers.remove(n.id);
						if (v) {
							editSend.notifiers.add(n.id);
						}
						changed();
					}, "Send", "Off"), 50);
				}
			}
		}
		header("Check");
		live(null, this::problems, GRAY);
	}

	private void triggerRows() {
		row("Trigger").add(cycle(KINDS, () -> rule.trigger.kind, k -> {
			rule.trigger.kind = k;
			ensureTriggerDefaults();
			structural();
		}, RuleEditScreen::kindLabel), -1);
		switch (rule.trigger.kind) {
			case Trigger.EVENT -> eventSection();
			case Trigger.CONDITION -> conditionSection();
			case Trigger.CHANGE -> changeSection();
			case Trigger.SCHEDULE -> scheduleSection();
			default -> {
			}
		}
	}

	@Override
	protected void buildRows() {
		templateBoxes.clear();
		if (section != null) {
			buildSection();
			return;
		}
		header("General");
		row("Name").add(box(rule.name, 100, v -> {
			rule.name = v;
			changed();
		}, "e.g. Low health"), -1);
		row("Enabled").add(toggle(() -> rule.enabled, v -> rule.enabled = v, "Yes", "No"), 70);

		header("When");
		row("Trigger").add(cycle(KINDS, () -> rule.trigger.kind, k -> {
			rule.trigger.kind = k;
			ensureTriggerDefaults();
			structural();
		}, RuleEditScreen::kindLabel), -1);
		switch (rule.trigger.kind) {
			case Trigger.EVENT -> eventSection();
			case Trigger.CONDITION -> conditionSection();
			case Trigger.CHANGE -> changeSection();
			case Trigger.SCHEDULE -> scheduleSection();
			default -> {
			}
		}

		header("Only if (optional)");
		if (rule.conditions == null) {
			row(null).add(button("+ Add condition", "Extra checks when the trigger fires, e.g. only while AFK", () -> {
				rule.conditions = Condition.group("all", newCompare());
				structural();
			}), 140);
		} else {
			condRows(rule.conditions, 0, () -> {
				rule.conditions = null;
				structural();
			});
		}

		messageSection(rule.message);
		targetSection();
		limitsSection();
		scopeSection();

		header("Check");
		live(null, this::problems, GRAY);
	}

	// ---- trigger sections ----

	private void eventSection() {
		EventSpec spec = rt.events.get(rule.trigger.event);
		row("Event").add(button(spec == null ? "Choose an event…" : spec.id(), this::pickEvent), -1);
		if (spec == null) {
			return;
		}
		text(Component.literal(spec.description()), GRAY, labelWidth);
		if (rule.trigger.params == null) {
			rule.trigger.params = new JsonObject();
		}
		FieldForm.build(this, spec.params(), rule.trigger.params, null, this::changed);
		if (!spec.payload().isEmpty()) {
			List<String> names = new ArrayList<>();
			for (PayloadVar v : spec.payload()) {
				names.add("{" + v.name() + "}");
			}
			text(Component.literal("Values: " + String.join(" ", names)), DARK, labelWidth);
		}
	}

	private void conditionSection() {
		Trigger t = rule.trigger;
		if (t.condition.isGroup()) {
			condRows(t.condition, 0, null);
		} else {
			condRows(t.condition, 0, null);
			row(null).indent(12).add(button("+ And…", "Add another condition (all must be true)", () -> {
				t.condition = Condition.group("all", t.condition, newCompare());
				structural();
			}), 60);
		}
		row("Fire").add(cycle(List.of("on_enter", "on_exit", "while_true"), () -> t.mode, v -> {
			t.mode = v;
			structural();
		}, v -> switch (v) {
			case "on_exit" -> "when it stops being true";
			case "while_true" -> "repeatedly while true";
			default -> "when it becomes true";
		}), -1);
		row("Must hold for (s)").add(box(num(t.forSeconds), 10, v -> {
			t.forSeconds = Math.max(0, parseNumber(v, 0));
			changed();
		}, "0"), 60);
		if ("while_true".equals(t.mode)) {
			row("Repeat every (s)").add(box(num(t.repeatSeconds), 10, v -> {
				t.repeatSeconds = Math.max(1, parseNumber(v, 60));
				changed();
			}, "60"), 60);
		}
		if ("on_enter".equals(t.mode)) {
			row("Re-arm only when").add(toggle(() -> t.rearm != null, v -> {
				t.rearm = v ? rearmFor(t.condition) : null;
				structural();
			}, "another condition is true", "it is false again"), -1);
			if (t.rearm != null) {
				condRows(t.rearm, 1, null);
			}
		}
		row("Check every (ticks)").add(box(String.valueOf(t.checkEveryTicks), 6, v -> {
			t.checkEveryTicks = (int) Math.max(1, parseNumber(v, 5));
			changed();
		}, "5"), 60);
	}

	private void changeSection() {
		Trigger t = rule.trigger;
		SignalRef ref = SignalRef.parse(t.signal);
		SignalSpec spec = ref == null ? null : rt.signals.spec(ref.id());
		row("Value").add(button(spec == null ? "Choose a value…" : spec.id(), () -> pickSignal(id -> {
			t.signal = id;
			structural();
		})), -1);
		if (spec != null) {
			text(Component.literal(spec.description()), GRAY, labelWidth);
			for (ParamSpec p : spec.params()) {
				row(p.label()).indent(12).add(box(ref.params().getOrDefault(p.name(), p.defaultValue().asString()), 100, v -> {
					Map<String, String> params = new LinkedHashMap<>(SignalRef.parse(t.signal).params());
					params.put(p.name(), v);
					t.signal = new SignalRef(ref.id(), params).toString();
					changed();
				}, p.defaultValue().asString()), -1);
			}
		}
		row("From (optional)").add(box(jsonText(t.from), 200, v -> {
			t.from = listOrValue(v);
			changed();
		}, "any"), -1);
		row("To (optional)").add(box(jsonText(t.to), 200, v -> {
			t.to = listOrValue(v);
			changed();
		}, "any"), -1);
		if (spec != null && spec.type() == ValueType.NUMBER) {
			row("Min change").add(box(num(t.minDelta), 10, v -> {
				t.minDelta = Math.max(0, parseNumber(v, 0));
				changed();
			}, "0"), 60);
		}
		row("Check every (ticks)").add(box(String.valueOf(t.checkEveryTicks), 6, v -> {
			t.checkEveryTicks = (int) Math.max(1, parseNumber(v, 5));
			changed();
		}, "5"), 60);
		text(Component.literal("Values: {old} {new}"), DARK, labelWidth);
	}

	private void scheduleSection() {
		Trigger t = rule.trigger;
		row("Every (seconds)").add(box(t.everySeconds > 0 ? num(t.everySeconds) : "", 10, v -> {
			t.everySeconds = Math.max(0, parseNumber(v, 0));
			changed();
		}, "e.g. 600"), 80);
		row("At world time").add(box(t.atWorldTime == null ? "" : String.valueOf(t.atWorldTime), 6, v -> {
			double d = parseNumber(v, Double.NaN);
			t.atWorldTime = Double.isNaN(d) ? null : (int) Math.floorMod((long) d, 24000L);
			changed();
		}, "ticks: 13000 = dusk"), 140);
		row("At real time").add(box(t.atRealTime == null ? "" : t.atRealTime, 5, v -> {
			t.atRealTime = v.isBlank() ? null : v.trim();
			changed();
		}, "HH:MM"), 80);
	}

	// ---- condition tree ----

	private Condition newCompare() {
		Condition c = new Condition();
		c.op = "eq";
		return c;
	}

	private Condition rearmFor(Condition c) {
		Condition r = newCompare();
		if (c != null && !c.isGroup() && c.left != null) {
			r.left = dev.sculkwire.core.util.Json.copy(c.left, Operand.class);
			Op op = Op.parse(c.op);
			r.op = op == Op.LT || op == Op.LTE ? "gte" : op == Op.GT || op == Op.GTE ? "lte" : "neq";
		}
		return r;
	}

	private void condRows(Condition c, int depth, Runnable onRemove) {
		int indent = depth * 14;
		if (c.isGroup()) {
			if (c.children == null) {
				c.children = new ArrayList<>();
			}
			Row r = row(null).indent(indent);
			r.add(cycle(List.of("all", "any", "none"), () -> c.op == null ? "all" : c.op, v -> {
				c.op = v;
				changed();
			}, v -> switch (v) {
				case "any" -> "Any of:";
				case "none" -> "None of:";
				default -> "All of:";
			}), 70);
			r.add(button("+ Condition", () -> {
				c.children.add(newCompare());
				structural();
			}), 80);
			r.add(button("+ Group", () -> {
				c.children.add(Condition.group("any", newCompare()));
				structural();
			}), 60);
			if (onRemove != null) {
				r.add(button("✖", "Remove this group", onRemove), 20);
			}
			for (Condition child : new ArrayList<>(c.children)) {
				condRows(child, depth + 1, () -> {
					c.children.remove(child);
					structural();
				});
			}
			return;
		}
		Row r = row(null).indent(indent);
		r.add(button(c.left == null ? "Choose value…" : c.left.describe(), "What to check", () -> pickOperand(op -> {
			c.left = op;
			fixOp(c);
			structural();
		})), 150);
		ValueType type = leftType(c);
		List<Op> ops = new ArrayList<>(Op.forType(type));
		Op current = Op.parse(c.op);
		if (current == null || !ops.contains(current)) {
			current = ops.get(0);
			c.op = current.id();
		}
		r.add(cycle(ops, () -> {
			Op o = Op.parse(c.op);
			return o == null ? ops.get(0) : o;
		}, o -> {
			int before = Op.parse(c.op) == null ? 1 : Op.parse(c.op).arity();
			c.op = o.id();
			if (o.arity() != before) {
				structural();
			} else {
				changed();
			}
		}, Op::label), 80);
		if (current.arity() >= 1) {
			r.add(valueWidget(c, type, false), -1);
		}
		if (current.arity() >= 2) {
			r.add(valueWidget(c, type, true), -1);
		}
		if (onRemove != null) {
			r.add(button("✖", "Remove", onRemove), 20);
		}
		if (c.left != null && c.left.signal != null) {
			SignalSpec spec = rt.signals.spec(SignalRef.parse(c.left.signal).id());
			if (spec != null) {
				for (ParamSpec p : spec.params()) {
					JsonElement cur = c.left.params == null ? null : c.left.params.get(p.name());
					row("  " + p.label()).indent(indent + 14).add(box(cur == null ? p.defaultValue().asString() : cur.getAsString(), 100, v -> {
						if (c.left.params == null) {
							c.left.params = new LinkedHashMap<>();
						}
						if (v.isBlank()) {
							c.left.params.remove(p.name());
						} else {
							c.left.params.put(p.name(), p.type() == ValueType.NUMBER && !Double.isNaN(parseNumber(v, Double.NaN))
									? new JsonPrimitive(parseNumber(v, 0)) : new JsonPrimitive(v));
						}
						changed();
					}, p.defaultValue().asString()), -1);
				}
			}
		}
	}

	private net.minecraft.client.gui.components.AbstractWidget valueWidget(Condition c, ValueType type, boolean second) {
		Operand existing = second ? c.right2 : c.right;
		Consumer<Operand> set = o -> {
			if (second) {
				c.right2 = o;
			} else {
				c.right = o;
			}
		};
		List<String> options = enumOptions(c);
		Op op = Op.parse(c.op);
		if ((type == ValueType.BOOL && op == Op.EQ) || (!options.isEmpty() && (op == Op.EQ || op == Op.NEQ))) {
			List<String> opts = type == ValueType.BOOL ? List.of("true", "false") : options;
			if (existing == null || existing.value == null) {
				set.accept(Operand.value(type == ValueType.BOOL ? (Object) Boolean.TRUE : opts.get(0)));
			}
			return cycle(opts, () -> {
				Operand o = second ? c.right2 : c.right;
				String v = o == null || o.value == null ? opts.get(0) : o.value.getAsString();
				return opts.contains(v) ? v : opts.get(0);
			}, v -> {
				set.accept(Operand.value(type == ValueType.BOOL ? (Object) Boolean.valueOf(v) : v));
				changed();
			}, v -> v);
		}
		String text = existing == null ? "" : existing.value != null ? jsonText(existing.value)
				: existing.var != null ? "{" + existing.var + "}" : existing.template != null ? existing.template : existing.describe();
		String hint = switch (type) {
			case NUMBER -> "number or {variable}";
			case ID -> "id, #tag or list";
			case LIST, STRING -> op == Op.MATCHES ? "regex" : "text";
			default -> "value";
		};
		if (op == Op.IN || op == Op.NOT_IN || op == Op.CONTAINS_ANY) {
			hint = "a, b, c or {list}";
		}
		return box(text, 500, v -> {
			set.accept(v.isBlank() ? null : Operand.value(literal(v, type)));
			changed();
		}, hint);
	}

	private static Object literal(String text, ValueType type) {
		String t = text.trim();
		if (type == ValueType.NUMBER) {
			double d = parseNumber(t, Double.NaN);
			if (!Double.isNaN(d)) {
				return d;
			}
		}
		if (type == ValueType.BOOL && (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false"))) {
			return Boolean.parseBoolean(t);
		}
		return text;
	}

	private List<String> enumOptions(Condition c) {
		if (c.left != null && c.left.signal != null) {
			SignalSpec s = rt.signals.spec(SignalRef.parse(c.left.signal).id());
			if (s != null && s.type() == ValueType.ENUM) {
				return s.enumOptions();
			}
		}
		return List.of();
	}

	private void fixOp(Condition c) {
		ValueType t = leftType(c);
		List<Op> ops = Op.forType(t);
		Op cur = Op.parse(c.op);
		if (cur == null || !ops.contains(cur)) {
			c.op = (t == ValueType.BOOL ? Op.IS_TRUE : t == ValueType.NUMBER ? Op.LT : ops.get(0)).id();
		}
		c.right = null;
		c.right2 = null;
	}

	private ValueType leftType(Condition c) {
		Map<String, ValueType> payload = payloadTypes();
		Map<String, ValueType> vars = new LinkedHashMap<>();
		for (UserVariable v : rt.config().variables) {
			vars.put(v.name, ValueType.parse(v.type));
		}
		return rt.engine.compiler().typeOf(c.left, payload, vars);
	}

	Map<String, ValueType> payloadTypes() {
		Map<String, ValueType> m = new LinkedHashMap<>();
		if (Trigger.EVENT.equals(rule.trigger.kind)) {
			EventSpec spec = rt.events.get(rule.trigger.event);
			if (spec != null) {
				for (PayloadVar v : spec.payload()) {
					m.put(v.name(), v.type());
				}
			}
		} else if (Trigger.CHANGE.equals(rule.trigger.kind)) {
			SignalRef ref = SignalRef.parse(rule.trigger.signal);
			SignalSpec s = ref == null ? null : rt.signals.spec(ref.id());
			ValueType t = s == null ? ValueType.NONE : s.type();
			m.put("old", t);
			m.put("new", t);
		}
		return m;
	}

	// ---- message, targets, limits, scope ----

	private void messageSection(MessageTemplate m) {
		previewMessage = m;
		header("Message");
		EditBox title = box(m.title, 500, v -> {
			m.title = v;
			changed();
		}, "e.g. Low health!");
		row("Title").add(title, -1);
		EditBox body = box(escapeNewlines(m.body), 4000, v -> {
			m.body = unescapeNewlines(v);
			changed();
		}, "e.g. {player.health|hearts} hearts left (\\n = new line)");
		row("Text").add(body, -1);
		templateBoxes.add(title);
		templateBoxes.add(body);
		row(null).add(button("Insert value…", "Insert a {variable} where the cursor was", this::insertVariable), 110)
				.live = () -> List.of(Component.literal("Filters: |hearts |int |pretty |clock |duration |upper |default:'x'"));
		rows.get(rows.size() - 1).textColor = DARK;
		row("Priority").add(cycle(PRIORITIES, () -> m.priority == null ? "default" : m.priority, v -> {
			m.priority = v;
			changed();
		}, v -> v), 90);
		row("Tags").add(box(String.join(", ", m.tags), 300, v -> {
			m.tags = Value.splitList(v);
			changed();
		}, "ntfy emoji tags, e.g. warning, skull"), -1);
		row("Click URL").add(box(m.click, 500, v -> {
			m.click = v;
			changed();
		}, "optional link"), -1);
		live("Preview", this::preview, WHITE);
	}

	private List<Component> preview() {
		if (compiled == null) {
			return List.of();
		}
		try {
			EvalContext ctx = rt.engine.previewContext(compiled);
			MessageTemplate pm = previewMessage == null ? rule.message : previewMessage;
			String t = Template.parse(pm.title).render(ctx, OutputContext.PLAIN);
			String b = Template.parse(pm.body).render(ctx, OutputContext.PLAIN);
			List<Component> out = new ArrayList<>();
			out.add(Component.literal(t.isEmpty() ? "(no title)" : t).withStyle(ChatFormatting.BOLD));
			for (String line : b.split("\n", -1)) {
				out.add(Component.literal(line));
			}
			return out;
		} catch (RuntimeException e) {
			return List.of(Component.literal("(preview failed: " + e.getMessage() + ")"));
		}
	}

	private void targetSection() {
		header("Send to");
		SculkwireConfig cfg = rt.config();
		if (cfg.notifiers.isEmpty()) {
			text("You have no notifiers yet. Add one on the Notifiers tab (ntfy, Discord, webhook or in-game).", WARN);
			return;
		}
		for (NotifierConfig n : cfg.notifiers) {
			Target existing = target(n.id);
			Row r = row(n.displayName() + " (" + n.type + ")");
			r.add(toggle(() -> target(n.id) != null, v -> {
				if (v) {
					rule.targets.add(Target.of(n.id));
				} else {
					rule.targets.removeIf(t -> t.notifier.equals(n.id));
				}
				structural();
			}, "Send", "Off"), 50);
			if (existing != null) {
				List<String> prio = new ArrayList<>();
				prio.add("");
				prio.addAll(PRIORITIES);
				r.add(cycle(prio, () -> existing.override == null || existing.override.priority == null ? "" : existing.override.priority, v -> {
					if (existing.override == null) {
						existing.override = new MessageTemplate();
					}
					existing.override.priority = v.isEmpty() ? null : v;
					changed();
				}, v -> v.isEmpty() ? "same priority" : "priority " + v), 120);
			}
		}
	}

	private Target target(String notifierId) {
		for (Target t : rule.targets) {
			if (t.notifier.equals(notifierId)) {
				return t;
			}
		}
		return null;
	}

	private void limitsSection() {
		var t = rule.throttle;
		header("Limits");
		row("Cooldown (s)").add(box(t.cooldownSeconds > 0 ? num(t.cooldownSeconds) : "", 10, v -> {
			t.cooldownSeconds = Math.max(0, parseNumber(v, 0));
			changed();
		}, "0 = none"), 80);
		row("Max per hour").add(box(t.maxPerHour > 0 ? String.valueOf(t.maxPerHour) : "", 6, v -> {
			t.maxPerHour = (int) Math.max(0, parseNumber(v, 0));
			changed();
		}, "0 = no limit"), 80);
		row("Once per session").add(toggle(() -> t.oncePerSession, v -> t.oncePerSession = v, "Yes", "No"), 60);
		row("Only when alt-tabbed").add(cycle(List.of("default", "yes", "no"),
				() -> t.onlyWhenUnfocused == null ? "default" : t.onlyWhenUnfocused ? "yes" : "no",
				v -> t.onlyWhenUnfocused = v.equals("default") ? null : v.equals("yes"),
				v -> v.equals("default") ? "use global setting" : v), 140);
		row("Ignore quiet hours").add(toggle(() -> t.ignoreQuietHours, v -> t.ignoreQuietHours = v, "Yes", "No"), 60);
		if (!Trigger.EVENT.equals(rule.trigger.kind)) {
			row("Fire if already true on join").add(toggle(() -> t.fireOnJoin, v -> t.fireOnJoin = v, "Yes", "No"), 60);
		}
	}

	private void scopeSection() {
		var s = rule.scope;
		header("Where");
		row("Singleplayer").add(toggle(() -> s.singleplayer, v -> s.singleplayer = v, "Yes", "No"), 60);
		row("Multiplayer").add(toggle(() -> s.multiplayer, v -> s.multiplayer = v, "Yes", "No"), 60);
		row("Only servers").add(box(String.join(", ", s.servers), 500, v -> {
			s.servers = Value.splitList(v);
			changed();
		}, "e.g. *.example.net (empty = any)"), -1);
		row("Only worlds").add(box(String.join(", ", s.worlds), 500, v -> {
			s.worlds = Value.splitList(v);
			changed();
		}, "singleplayer world names (empty = any)"), -1);
		row("Only dimensions").add(box(String.join(", ", s.dimensions), 500, v -> {
			s.dimensions = Value.splitList(v);
			changed();
		}, "e.g. the_nether (empty = any)"), -1);
	}

	private List<Component> problems() {
		List<Component> out = new ArrayList<>();
		if (compiled == null) {
			return out;
		}
		for (String e : compiled.errors) {
			out.add(Component.literal("✖ " + e).withStyle(ChatFormatting.RED));
		}
		for (String w : compiled.warnings) {
			out.add(Component.literal("⚠ " + w).withStyle(ChatFormatting.GOLD));
		}
		if (out.isEmpty()) {
			out.add(Component.literal("✔ No problems").withStyle(ChatFormatting.GREEN));
		}
		if (!isNew) {
			RuleEngine.Status st = rt.engine.status(originalId);
			if (!st.outcome().isEmpty()) {
				out.add(Component.literal("Last time: " + st.outcome()).withStyle(ChatFormatting.GRAY));
			}
		}
		return out;
	}

	// ---- pickers ----

	private void pickEvent() {
		List<PickerScreen.Item<String>> items = new ArrayList<>();
		for (EventSpec e : rt.events.all()) {
			items.add(new PickerScreen.Item<>(e.id(), e.description(), cap(e.category()), e.id()));
		}
		minecraft.gui.setScreen(new PickerScreen<>(this, "Choose an event", items, id -> {
			rule.trigger.event = id;
			rule.trigger.params = new JsonObject();
			structural();
		}));
	}

	private void pickSignal(Consumer<String> onPick) {
		List<PickerScreen.Item<String>> items = new ArrayList<>();
		for (SignalSpec s : rt.signals.specs()) {
			items.add(new PickerScreen.Item<>(s.id(), s.description() + typeSuffix(s), cap(s.category()), s.id()));
		}
		minecraft.gui.setScreen(new PickerScreen<>(this, "Choose a value", items, onPick));
	}

	void pickOperand(Consumer<Operand> onPick) {
		pickOperandFrom(this, onPick);
	}

	/** Opens the value picker with {@code returnTo} as the screen to go back to. */
	void pickOperandFrom(Screen returnTo, Consumer<Operand> onPick) {
		List<PickerScreen.Item<Operand>> items = new ArrayList<>();
		for (Map.Entry<String, ValueType> e : payloadTypes().entrySet()) {
			items.add(new PickerScreen.Item<>(e.getKey(), "from this trigger (" + e.getValue().id() + ")", "This trigger", Operand.payload(e.getKey())));
		}
		for (UserVariable v : rt.config().variables) {
			items.add(new PickerScreen.Item<>(v.name, "your variable (" + v.type.toLowerCase(Locale.ROOT) + ")", "Your variables", Operand.var(v.name)));
		}
		for (SignalSpec s : rt.signals.specs()) {
			items.add(new PickerScreen.Item<>(s.id(), s.description() + typeSuffix(s), cap(s.category()), Operand.signal(s.id())));
		}
		minecraft.gui.setScreen(new PickerScreen<>(returnTo, "Choose what to check", items, onPick));
	}

	private void insertVariable() {
		EditBox target = lastTemplateBox;
		List<PickerScreen.Item<String>> items = new ArrayList<>();
		for (Map.Entry<String, ValueType> e : payloadTypes().entrySet()) {
			items.add(new PickerScreen.Item<>(e.getKey(), "from this trigger", "This trigger", e.getKey()));
		}
		if (Trigger.EVENT.equals(rule.trigger.kind) && rule.trigger.params != null) {
			items.add(new PickerScreen.Item<>("match.1", "first regex group", "This trigger", "match.1"));
		}
		items.add(new PickerScreen.Item<>("rule.name", "this event's name", "Other", "rule.name"));
		items.add(new PickerScreen.Item<>("now", "current time (use |date:'HH:mm')", "Other", "now|date:'HH:mm'"));
		for (UserVariable v : rt.config().variables) {
			items.add(new PickerScreen.Item<>(v.name, "your variable", "Your variables", v.name));
		}
		for (SignalSpec s : rt.signals.specs()) {
			String insert = s.id();
			if (!s.params().isEmpty()) {
				List<String> ps = new ArrayList<>();
				for (ParamSpec p : s.params()) {
					ps.add(p.name() + "=" + p.defaultValue().asString());
				}
				insert = s.id() + "(" + String.join(",", ps) + ")";
			}
			items.add(new PickerScreen.Item<>(s.id(), s.description(), cap(s.category()), insert));
		}
		minecraft.gui.setScreen(new PickerScreen<>(this, "Insert a value", items, name -> {
			EditBox b = target != null ? target : templateBoxes.isEmpty() ? null : templateBoxes.get(templateBoxes.size() - 1);
			if (b != null) {
				b.insertText("{" + name + "}");
			}
		}));
	}

	// ---- actions ----

	private void save() {
		if (rule.name == null || rule.name.isBlank()) {
			rule.name = isNew ? "New event" : originalId;
		}
		SculkwireConfig cfg = rt.config();
		if (isNew || cfg.rule(originalId) == null) {
			rule.id = cfg.uniqueId(rule.name);
			cfg.rules.add(rule);
		} else {
			rule.id = originalId;
			for (int i = 0; i < cfg.rules.size(); i++) {
				if (cfg.rules.get(i).id.equals(originalId)) {
					cfg.rules.set(i, rule);
				}
			}
		}
		rt.apply(cfg);
		onClose();
	}

	private void test() {
		if (rule.targets.isEmpty()) {
			ClientRuntime.warn("Choose at least one notifier under 'Send to' first.");
			return;
		}
		rt.engine.testFire(rule);
		ClientRuntime.info("Test of '" + rule.name + "' sent; see the History tab for the result.");
	}

	// ---- helpers ----

	static String kindLabelFor(String kind) {
		return kindLabel(kind);
	}

	static String kindLabel(String kind) {
		return switch (kind) {
			case Trigger.CONDITION -> "A condition becomes true";
			case Trigger.CHANGE -> "A value changes";
			case Trigger.SCHEDULE -> "On a schedule";
			default -> "Something happens (event)";
		};
	}

	private static String typeSuffix(SignalSpec s) {
		return " [" + s.type().id() + (s.unit().isEmpty() ? "" : ", " + s.unit()) + "]";
	}

	static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String num(double d) {
		return Value.formatNumber(d);
	}

	private static String jsonText(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return "";
		}
		if (e.isJsonArray()) {
			List<String> l = new ArrayList<>();
			e.getAsJsonArray().forEach(x -> l.add(x.getAsString()));
			return String.join(", ", l);
		}
		return e.isJsonPrimitive() ? Value.fromJson(e).asString() : e.toString();
	}

	private static JsonElement listOrValue(String v) {
		if (v == null || v.isBlank()) {
			return null;
		}
		List<String> parts = Value.splitList(v);
		if (parts.size() > 1) {
			JsonArray a = new JsonArray();
			parts.forEach(a::add);
			return a;
		}
		return new JsonPrimitive(v.trim());
	}

	@SuppressWarnings("unused")
	private static String strip(String s) {
		return Filters.stripFormatting(s);
	}
}
