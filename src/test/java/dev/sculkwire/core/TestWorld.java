package dev.sculkwire.core;

import dev.sculkwire.core.engine.RuleEngine;
import dev.sculkwire.core.event.BuiltinEvents;
import dev.sculkwire.core.event.EventRegistry;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Target;
import dev.sculkwire.core.signal.BuiltinSignals;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.util.Clock;
import dev.sculkwire.core.value.Value;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A fake game for engine tests: settable signal values, fake clock, recorded deliveries. */
public final class TestWorld {
	public static final class FakeClock implements Clock {
		public long t = 1_700_000_000_000L;

		@Override
		public long millis() {
			return t;
		}

		@Override
		public ZoneId zone() {
			return ZoneOffset.UTC;
		}
	}

	public final SignalRegistry signals = new SignalRegistry();
	public final EventRegistry events = new EventRegistry();
	public final Map<String, Value> values = new HashMap<>();
	public final FakeClock clock = new FakeClock();
	public final List<RuleEngine.Delivery> sent = new ArrayList<>();
	public final RuleEngine engine;
	public final SculkwireConfig config = new SculkwireConfig();

	public TestWorld() {
		BuiltinSignals.defineAll(signals);
		for (SignalSpec spec : signals.specs()) {
			String id = spec.id();
			signals.source(id, p -> values.getOrDefault(id, Value.NONE));
		}
		BuiltinEvents.defineAll(events);
		engine = new RuleEngine(signals, events, clock, sent::add);
		config.notifiers.add(NotifierConfig.of("phone", "Phone", "ntfy"));
		set("player.name", "Steve");
		set("world.is_singleplayer", true);
		set("session.window_focused", true);
	}

	public TestWorld set(String id, Object v) {
		values.put(id, Value.of(v));
		return this;
	}

	public TestWorld add(Rule r) {
		if (r.targets.isEmpty()) {
			r.targets.add(Target.of("phone"));
		}
		if (r.id.isEmpty()) {
			r.id = config.uniqueId(r.name.isEmpty() ? "rule" : r.name);
		}
		config.rules.add(r);
		engine.load(config);
		return this;
	}

	/** Joins and runs past the join grace period. */
	public TestWorld join() {
		engine.onJoin();
		ticks(60);
		return this;
	}

	public TestWorld ticks(int n) {
		for (int i = 0; i < n; i++) {
			engine.tick();
			clock.t += 50;
		}
		return this;
	}

	public TestWorld seconds(double s) {
		return ticks((int) Math.round(s * 20));
	}

	public int count() {
		return sent.size();
	}

	public String lastBody() {
		return sent.get(sent.size() - 1).notification().body(OutputContext.PLAIN);
	}

	public String lastTitle() {
		return sent.get(sent.size() - 1).notification().title(OutputContext.PLAIN);
	}
}
