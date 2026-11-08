package dev.sculkwire.core.engine;

import dev.sculkwire.core.value.Value;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Runtime state of one rule (edge detection, debounce, throttle). */
final class RuleState {
	static final long NEVER = Long.MIN_VALUE;

	boolean primed;
	boolean armed = true;
	boolean lastEff;
	long trueSince = -1;
	long lastRepeat = NEVER;
	Value prevValue;
	long lastScheduled;
	double prevWorldTime = Double.NaN;
	LocalDate lastDate;

	long lastFiredAt = NEVER;
	final ArrayDeque<Long> recent = new ArrayDeque<>();
	boolean firedThisSession;
	String lastOutcome = "";
	long lastOutcomeAt;
	final Map<Object, Value> memory = new HashMap<>();

	void resetSession() {
		primed = false;
		armed = true;
		lastEff = false;
		trueSince = -1;
		lastRepeat = NEVER;
		prevValue = null;
		lastScheduled = 0;
		prevWorldTime = Double.NaN;
		firedThisSession = false;
		memory.clear();
	}
}
