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
	/** Optional if / else-if / else chain. When present it replaces {@link #message} and {@link #targets}. */
	public List<Branch> branches;

	public boolean hasBranches() {
		return branches != null && !branches.isEmpty();
	}

	/** All notifier ids this rule can send to (targets, or every send in the branches). */
	public List<String> notifierIds() {
		List<String> out = new ArrayList<>();
		if (hasBranches()) {
			Branch.forEach(branches, b -> {
				if (b.sends != null) {
					for (Send s : b.sends) {
						if (s != null && s.notifiers != null) {
							for (String n : s.notifiers) {
								if (!out.contains(n)) {
									out.add(n);
								}
							}
						}
					}
				}
			});
		} else if (targets != null) {
			for (Target t : targets) {
				if (!out.contains(t.notifier)) {
					out.add(t.notifier);
				}
			}
		}
		return out;
	}

	public Rule copy() {
		return Json.copy(this, Rule.class);
	}
}
