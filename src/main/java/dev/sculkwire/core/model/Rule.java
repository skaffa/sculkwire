package dev.sculkwire.core.model;

import dev.sculkwire.core.util.Json;

import java.util.ArrayList;
import java.util.List;

/** A user rule (called an "Event" in the UI): trigger, conditions, scope, throttle, message, targets. */
public final class Rule {
	public String id = "";
	public String name = "";
	public boolean enabled = true;
	public Trigger trigger = new Trigger();
	public Condition conditions;
	public Scope scope = new Scope();
	public Throttle throttle = new Throttle();
	public MessageTemplate message = MessageTemplate.of("", "");
	public List<Target> targets = new ArrayList<>();

	public Rule copy() {
		return Json.copy(this, Rule.class);
	}
}
