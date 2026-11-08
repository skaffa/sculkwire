package dev.sculkwire.core.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What makes a rule consider firing. {@code kind} is one of {@code event}, {@code condition},
 * {@code change} or {@code schedule}; only the fields for that kind are used.
 */
public final class Trigger {
	public static final String EVENT = "event";
	public static final String CONDITION = "condition";
	public static final String CHANGE = "change";
	public static final String SCHEDULE = "schedule";

	public String kind = EVENT;

	// event
	public String event;
	public JsonObject params;

	// condition
	/** on_enter (false to true), on_exit (true to false) or while_true (repeats). */
	public Condition condition;
	public String mode = "on_enter";
	/** Optional re-arm condition (hysteresis) for on_enter. */
	public Condition rearm;
	/** The condition must hold this long before it counts as true. */
	public double forSeconds;
	public int checkEveryTicks = 5;
	public double repeatSeconds = 60;

	// change
	/** Signal reference such as {@code player.dimension} or {@code nearby.hostiles(radius=16)}. */
	public String signal;
	public JsonElement from;
	public JsonElement to;
	public double minDelta;

	// schedule
	public double everySeconds;
	public Integer atWorldTime;
	/** Real-world time HH:MM. */
	public String atRealTime;

	public static Trigger event(String id) {
		Trigger t = new Trigger();
		t.kind = EVENT;
		t.event = id;
		t.params = new JsonObject();
		return t;
	}

	public static Trigger condition(Condition c) {
		Trigger t = new Trigger();
		t.kind = CONDITION;
		t.condition = c;
		return t;
	}

	public static Trigger change(String signal) {
		Trigger t = new Trigger();
		t.kind = CHANGE;
		t.signal = signal;
		return t;
	}

	public static Trigger every(double seconds) {
		Trigger t = new Trigger();
		t.kind = SCHEDULE;
		t.everySeconds = seconds;
		return t;
	}
}
