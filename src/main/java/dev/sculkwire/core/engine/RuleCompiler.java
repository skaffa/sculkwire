package dev.sculkwire.core.engine;

import com.google.gson.JsonElement;

import dev.sculkwire.core.event.EventRegistry;
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
import dev.sculkwire.core.notify.Priority;
import dev.sculkwire.core.signal.SignalRef;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.util.SafeRegex;
import dev.sculkwire.core.value.Op;
import dev.sculkwire.core.value.Ops;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;

/** Validates and compiles {@link Rule}s. */
public final class RuleCompiler {
	private final SignalRegistry signals;
	private final EventRegistry events;

	public RuleCompiler(SignalRegistry signals, EventRegistry events) {
		this.signals = signals;
		this.events = events;
	}

	private interface OperandFn {
		Value get(EvalContext c);
	}

	private final class Env {
		final CompiledRule cr;
		final Map<String, ValueType> payloadTypes = new HashMap<>();
		final Map<String, ValueType> varTypes = new HashMap<>();

		Env(CompiledRule cr) {
			this.cr = cr;
		}

		boolean known(String name) {
			if (payloadTypes.containsKey(name) || EvalContext.BUILTINS.contains(name) || signals.spec(name) != null) {
				return true;
			}
			if (name.startsWith("match.") && cr.kind == CompiledRule.Kind.EVENT) {
				return true;
			}
			return varTypes.containsKey(name.startsWith("var.") ? name.substring(4) : name);
		}

		Predicate<String> knownFn() {
			return this::known;
		}
	}

	public CompiledRule compile(Rule rule, SculkwireConfig cfg) {
		CompiledRule cr = new CompiledRule(rule);
		Env env = new Env(cr);
		for (UserVariable v : cfg.variables) {
			env.varTypes.put(v.name, ValueType.parse(v.type));
		}
		Trigger t = rule.trigger == null ? new Trigger() : rule.trigger;
		cr.kind = switch (t.kind == null ? "" : t.kind.toLowerCase(Locale.ROOT)) {
			case Trigger.EVENT -> CompiledRule.Kind.EVENT;
			case Trigger.CONDITION -> CompiledRule.Kind.CONDITION;
			case Trigger.CHANGE -> CompiledRule.Kind.CHANGE;
			case Trigger.SCHEDULE -> CompiledRule.Kind.SCHEDULE;
			default -> null;
		};
		if (cr.kind == null) {
			cr.errors.add("Unknown trigger kind '" + t.kind + "'");
			cr.kind = CompiledRule.Kind.EVENT;
			return cr;
		}

		switch (cr.kind) {
			case EVENT -> {
				cr.event = events.get(t.event);
				if (cr.event == null) {
					cr.errors.add(t.event == null || t.event.isBlank() ? "No event selected" : "Unknown event '" + t.event + "'");
				} else {
					for (PayloadVar v : cr.event.payload()) {
						env.payloadTypes.put(v.name(), v.type());
					}
				}
			}
			case CONDITION -> {
				cr.mode = switch (t.mode == null ? "on_enter" : t.mode.toLowerCase(Locale.ROOT)) {
					case "on_enter" -> CompiledRule.Mode.ON_ENTER;
					case "on_exit" -> CompiledRule.Mode.ON_EXIT;
					case "while_true" -> CompiledRule.Mode.WHILE_TRUE;
					default -> {
						cr.errors.add("Unknown mode '" + t.mode + "'");
						yield CompiledRule.Mode.ON_ENTER;
					}
				};
				if (t.condition == null) {
					cr.errors.add("The trigger has no condition");
				} else {
					cr.trigger = cond(t.condition, env);
				}
				if (t.rearm != null) {
					cr.rearm = cond(t.rearm, env);
				}
				cr.forSeconds = Math.max(0, t.forSeconds);
				cr.repeatMs = (long) (Math.max(1, t.repeatSeconds) * 1000);
				cr.interval = Math.max(1, t.checkEveryTicks);
			}
			case CHANGE -> {
				env.payloadTypes.put("old", ValueType.NONE);
				env.payloadTypes.put("new", ValueType.NONE);
				SignalRef ref = SignalRef.parse(t.signal);
				SignalSpec spec = ref == null ? null : signals.spec(ref.id());
				if (spec == null) {
					cr.errors.add(ref == null ? "No signal selected" : "Unknown signal '" + ref.id() + "'");
				} else {
					cr.change = ref;
					env.payloadTypes.put("old", spec.type());
					env.payloadTypes.put("new", spec.type());
				}
				cr.from = t.from == null ? null : Value.fromJson(t.from);
				cr.to = t.to == null ? null : Value.fromJson(t.to);
				cr.minDelta = Math.max(0, t.minDelta);
				cr.interval = Math.max(1, t.checkEveryTicks);
			}
			case SCHEDULE -> {
				cr.everyMs = (long) (Math.max(0, t.everySeconds) * 1000);
				cr.atWorldTime = t.atWorldTime;
				if (t.atRealTime != null && !t.atRealTime.isBlank()) {
					try {
						cr.atRealTime = LocalTime.parse(t.atRealTime.trim().length() == 4 ? "0" + t.atRealTime.trim() : t.atRealTime.trim());
					} catch (DateTimeParseException e) {
						cr.errors.add("Real time '" + t.atRealTime + "' is not HH:MM");
					}
				}
				if (cr.everyMs <= 0 && cr.atWorldTime == null && cr.atRealTime == null && cr.errors.isEmpty()) {
					cr.errors.add("The schedule has no interval or time");
				}
				if (cr.everyMs > 0 && cr.everyMs < 1000) {
					cr.errors.add("The schedule interval must be at least 1 second");
				}
				cr.interval = cr.atWorldTime != null ? 5 : 20;
			}
		}

		if (rule.conditions != null) {
			cr.conditions = cond(rule.conditions, env);
		}

		MessageTemplate base = rule.message == null ? MessageTemplate.of("", "") : rule.message;
		if (isBlank(base.title) && isBlank(base.body)) {
			cr.warnings.add("The message is empty");
		}
		if (rule.targets == null || rule.targets.isEmpty()) {
			cr.warnings.add("No notifiers selected: this event will not send anything");
		} else {
			for (Target target : rule.targets) {
				NotifierConfig nc = cfg.notifier(target.notifier);
				if (nc == null) {
					cr.warnings.add("Notifier '" + target.notifier + "' does not exist");
				}
				MessageTemplate m = base.merge(target.override);
				Template title = Template.parse(m.title);
				Template body = Template.parse(m.body);
				Template click = Template.parse(m.click);
				for (Template tpl : List.of(title, body, click)) {
					for (String p : tpl.problems(env.knownFn())) {
						if (!cr.warnings.contains("Message: " + p)) {
							cr.warnings.add("Message: " + p);
						}
					}
				}
				cr.targets.add(new CompiledRule.CompiledTarget(target.notifier, title, body, click, Priority.parse(m.priority),
						m.tags == null ? List.of() : List.copyOf(m.tags)));
			}
		}
		if (cr.targets.isEmpty()) {
			for (String field : new String[] {base.title, base.body}) {
				for (String p : Template.parse(field).problems(env.knownFn())) {
					cr.warnings.add("Message: " + p);
				}
			}
		}
		return cr;
	}

	/** Returns the value type of an operand, or NONE when unknown. */
	public ValueType typeOf(Operand o, Map<String, ValueType> payloadTypes, Map<String, ValueType> varTypes) {
		if (o == null) {
			return ValueType.NONE;
		}
		if (o.signal != null) {
			SignalRef ref = SignalRef.parse(o.signal);
			SignalSpec s = ref == null ? null : signals.spec(ref.id());
			return s == null ? ValueType.NONE : s.type();
		}
		if (o.payload != null) {
			return payloadTypes.getOrDefault(o.payload, ValueType.NONE);
		}
		if (o.var != null) {
			return varTypes.getOrDefault(o.var.startsWith("var.") ? o.var.substring(4) : o.var, ValueType.NONE);
		}
		if (o.template != null) {
			return ValueType.STRING;
		}
		if (o.value != null) {
			return Value.fromJson(o.value).type();
		}
		return ValueType.NONE;
	}

	private Cond cond(Condition c, Env env) {
		if (c == null) {
			env.cr.errors.add("Empty condition");
			return Cond.FALSE;
		}
		if (c.isGroup()) {
			List<Cond> kids = new ArrayList<>();
			if (c.children != null) {
				for (Condition child : c.children) {
					kids.add(cond(child, env));
				}
			}
			String op = c.op == null ? "all" : c.op.toLowerCase(Locale.ROOT);
			return switch (op) {
				case "all" -> ctx -> {
					for (Cond k : kids) {
						if (!k.test(ctx)) {
							return false;
						}
					}
					return true;
				};
				case "any" -> ctx -> {
					for (Cond k : kids) {
						if (k.test(ctx)) {
							return true;
						}
					}
					return false;
				};
				case "none" -> ctx -> {
					for (Cond k : kids) {
						if (k.test(ctx)) {
							return false;
						}
					}
					return true;
				};
				default -> {
					env.cr.errors.add("Unknown group operator '" + c.op + "' (use all, any or none)");
					yield Cond.FALSE;
				}
			};
		}
		Op op = Op.parse(c.op);
		if (op == null) {
			env.cr.errors.add("Unknown operator '" + c.op + "'");
			return Cond.FALSE;
		}
		if (c.left == null || c.left.isEmpty()) {
			env.cr.errors.add("A condition has no left side");
			return Cond.FALSE;
		}
		OperandFn left = operand(c.left, env, false);
		ValueType lt = typeOf(c.left, env.payloadTypes, env.varTypes);
		if (lt != ValueType.NONE && !Op.forType(lt).contains(op)) {
			env.cr.errors.add("'" + op.label() + "' does not apply to " + lt.id() + " value " + c.left.describe());
		}
		boolean pattern = op == Op.MATCHES;
		OperandFn a = null;
		OperandFn b = null;
		if (op.arity() >= 1) {
			if (c.right == null || c.right.isEmpty()) {
				env.cr.errors.add("'" + c.left.describe() + " " + op.label() + "' needs a value");
			} else {
				a = operand(c.right, env, pattern);
				if (pattern && c.right.value != null && c.right.value.isJsonPrimitive()) {
					String re = c.right.value.getAsString();
					if (!Template.parse(re).hasExpressions()) {
						try {
							SafeRegex.compile(re, c.ignoreCase);
						} catch (PatternSyntaxException e) {
							env.cr.errors.add("Invalid regex '" + re + "': " + e.getDescription());
						}
					}
				}
			}
		}
		if (op.arity() >= 2) {
			if (c.right2 == null || c.right2.isEmpty()) {
				env.cr.errors.add("'" + op.label() + "' needs two values");
			} else {
				b = operand(c.right2, env, false);
			}
		}
		return new CompareCond(left, op, a, b, c.ignoreCase);
	}

	private OperandFn operand(Operand o, Env env, boolean pattern) {
		if (o.signal != null) {
			SignalRef ref = SignalRef.parse(o.signal);
			if (ref == null || signals.spec(ref.id()) == null) {
				env.cr.errors.add("Unknown signal '" + o.signal + "'");
				return c -> Value.NONE;
			}
			Map<String, String> params = new LinkedHashMap<>(ref.params());
			if (o.params != null) {
				for (Map.Entry<String, JsonElement> e : o.params.entrySet()) {
					if (e.getValue() != null && !e.getValue().isJsonNull()) {
						params.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
					}
				}
			}
			String id = ref.id();
			Map<String, String> p = Map.copyOf(params);
			return c -> c.signal(id, p);
		}
		if (o.payload != null) {
			String name = o.payload;
			if (!env.payloadTypes.containsKey(name) && !name.startsWith("match.")) {
				env.cr.warnings.add("This trigger has no value '" + name + "'");
			}
			return c -> c.payload(name);
		}
		if (o.var != null) {
			String name = o.var;
			if (!env.varTypes.containsKey(name.startsWith("var.") ? name.substring(4) : name)) {
				env.cr.warnings.add("Unknown variable '" + name + "'");
			}
			return c -> c.var(name);
		}
		if (o.template != null) {
			Template t = Template.parse(o.template);
			for (String p : t.problems(env.knownFn())) {
				env.cr.warnings.add("Condition: " + p);
			}
			OutputContext oc = pattern ? OutputContext.REGEX : OutputContext.PLAIN;
			return c -> Value.str(t.render(c, oc));
		}
		if (o.value != null) {
			Value v = Value.fromJson(o.value);
			if (v.type() == ValueType.STRING) {
				Template t = Template.parse(v.asString());
				if (t.hasExpressions()) {
					OutputContext oc = pattern ? OutputContext.REGEX : OutputContext.PLAIN;
					return c -> Value.str(t.render(c, oc));
				}
			}
			return c -> v;
		}
		env.cr.errors.add("A condition value is empty");
		return c -> Value.NONE;
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static final class CompareCond implements Cond {
		private final OperandFn left;
		private final Op op;
		private final OperandFn a;
		private final OperandFn b;
		private final boolean ignoreCase;

		CompareCond(OperandFn left, Op op, OperandFn a, OperandFn b, boolean ignoreCase) {
			this.left = left;
			this.op = op;
			this.a = a;
			this.b = b;
			this.ignoreCase = ignoreCase;
		}

		@Override
		public boolean test(EvalContext ctx) {
			Value l = left.get(ctx);
			if (op == Op.CHANGED) {
				Value prev = ctx.memory().put(this, l);
				return prev != null && !prev.isNone() && !l.isNone() && !prev.equals(l);
			}
			return Ops.test(op, l, a == null ? null : a.get(ctx), b == null ? null : b.get(ctx), ignoreCase, ctx.tags());
		}
	}

	/** Payload types of an event (for the UI's operand pickers). */
	public static Map<String, ValueType> payloadTypes(EventSpec spec) {
		Map<String, ValueType> m = new LinkedHashMap<>();
		if (spec != null) {
			for (PayloadVar v : spec.payload()) {
				m.put(v.name(), v.type());
			}
		}
		return m;
	}
}
