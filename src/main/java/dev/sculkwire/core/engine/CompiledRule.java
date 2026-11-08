package dev.sculkwire.core.engine;

import dev.sculkwire.core.event.EventSpec;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.notify.Priority;
import dev.sculkwire.core.signal.SignalRef;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.value.Value;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** A rule after validation and compilation. Rules with errors stay inactive; warnings are shown only. */
public final class CompiledRule {
	public enum Kind {
		EVENT,
		CONDITION,
		CHANGE,
		SCHEDULE
	}

	public enum Mode {
		ON_ENTER,
		ON_EXIT,
		WHILE_TRUE
	}

	public record CompiledTarget(String notifierId, Template title, Template body, Template click, Priority priority,
			List<String> tags) {
		public List<Template> templates() {
			return List.of(title, body, click);
		}
	}

	public final Rule rule;
	public Kind kind;
	public Mode mode = Mode.ON_ENTER;
	public EventSpec event;
	public Cond trigger;
	public Cond rearm;
	public Cond conditions;
	public SignalRef change;
	public Value from;
	public Value to;
	public double minDelta;
	public double forSeconds;
	public long repeatMs;
	public long everyMs;
	public Integer atWorldTime;
	public LocalTime atRealTime;
	public int interval = 5;
	public final List<CompiledTarget> targets = new ArrayList<>();
	public final List<String> errors = new ArrayList<>();
	public final List<String> warnings = new ArrayList<>();

	public CompiledRule(Rule rule) {
		this.rule = rule;
	}

	public boolean active() {
		return rule.enabled && errors.isEmpty();
	}
}
