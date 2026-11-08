package dev.sculkwire.core.signal;

import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** All known signals: specs plus (on the client) their sources. */
public final class SignalRegistry implements SignalReader {
	private static final Logger LOG = LoggerFactory.getLogger("Sculkwire");

	private final Map<String, SignalSpec> specs = new LinkedHashMap<>();
	private final Map<String, SignalSource> sources = new LinkedHashMap<>();
	private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());

	public void define(SignalSpec spec) {
		specs.put(spec.id(), spec);
	}

	public void source(String id, SignalSource source) {
		if (!specs.containsKey(id)) {
			throw new IllegalArgumentException("No signal spec for " + id);
		}
		sources.put(id, source);
	}

	public void register(SignalSpec spec, SignalSource source) {
		define(spec);
		sources.put(spec.id(), source);
	}

	@Override
	public SignalSpec spec(String id) {
		return id == null ? null : specs.get(id);
	}

	public Collection<SignalSpec> specs() {
		return Collections.unmodifiableCollection(specs.values());
	}

	public boolean hasSource(String id) {
		return sources.containsKey(id);
	}

	public List<String> categories() {
		List<String> out = new ArrayList<>();
		for (SignalSpec s : specs.values()) {
			if (!out.contains(s.category())) {
				out.add(s.category());
			}
		}
		return out;
	}

	/** Coerces given params to the spec's types, fills defaults and drops unknown keys. */
	public Map<String, Value> resolveParams(SignalSpec spec, Map<String, ?> given) {
		if (spec.params().isEmpty()) {
			return Map.of();
		}
		Map<String, Value> out = new TreeMap<>();
		for (ParamSpec p : spec.params()) {
			Object raw = given == null ? null : given.get(p.name());
			Value v = raw == null ? null : raw instanceof Value val ? val : Value.str(raw.toString());
			out.put(p.name(), p.resolve(v));
		}
		return out;
	}

	@Override
	public Value read(String id, Map<String, ?> params) {
		SignalSpec spec = specs.get(id);
		if (spec == null) {
			return Value.NONE;
		}
		SignalSource src = sources.get(id);
		if (src == null) {
			return Value.NONE;
		}
		try {
			Value v = src.read(resolveParams(spec, params));
			if (v == null) {
				return Value.NONE;
			}
			if (!v.isNone() && v.type() != spec.type() && spec.type() != ValueType.NONE) {
				v = v.coerce(spec.type());
			}
			return v;
		} catch (RuntimeException e) {
			if (failed.add(id)) {
				LOG.warn("Signal {} failed: {}", id, e.toString());
			}
			return Value.NONE;
		}
	}
}
