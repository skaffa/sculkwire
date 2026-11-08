package dev.sculkwire.core.engine;

import dev.sculkwire.core.signal.SignalReader;
import dev.sculkwire.core.signal.SignalRegistry;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.value.Value;

import java.util.HashMap;
import java.util.Map;

/**
 * Memoizes signal reads: within one tick each (signal, params) is read once; expensive signals are
 * reused for up to their {@code minIntervalTicks}.
 */
public final class TickCache implements SignalReader {
	private record Key(String id, Map<String, Value> params) {
	}

	private record Entry(Value value, long tick) {
	}

	private final SignalRegistry registry;
	private final Map<Key, Entry> entries = new HashMap<>();
	private long tick;

	public TickCache(SignalRegistry registry) {
		this.registry = registry;
	}

	public void newTick(long t) {
		tick = t;
		if (entries.size() > 4096) {
			entries.clear();
		}
	}

	public void clear() {
		entries.clear();
	}

	@Override
	public Value read(String id, Map<String, ?> params) {
		SignalSpec spec = registry.spec(id);
		if (spec == null) {
			return Value.NONE;
		}
		Map<String, Value> resolved = registry.resolveParams(spec, params);
		Key k = new Key(id, resolved);
		Entry e = entries.get(k);
		int ttl = Math.max(1, spec.minIntervalTicks());
		if (e != null && tick - e.tick() < ttl && tick >= e.tick()) {
			return e.value();
		}
		Value v = registry.read(id, resolved);
		entries.put(k, new Entry(v, tick));
		return v;
	}

	@Override
	public SignalSpec spec(String id) {
		return registry.spec(id);
	}
}
