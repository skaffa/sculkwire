package dev.sculkwire.core.notify;

import dev.sculkwire.core.notify.types.DiscordType;
import dev.sculkwire.core.notify.types.GotifyType;
import dev.sculkwire.core.notify.types.NtfyType;
import dev.sculkwire.core.notify.types.PushoverType;
import dev.sculkwire.core.notify.types.TelegramType;
import dev.sculkwire.core.notify.types.WebhookType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All notifier types by id. */
public final class NotifierRegistry {
	private final Map<String, NotifierType> types = new LinkedHashMap<>();

	public static NotifierRegistry withBuiltins() {
		NotifierRegistry r = new NotifierRegistry();
		r.register(new NtfyType());
		r.register(new DiscordType());
		r.register(new WebhookType());
		r.register(new TelegramType());
		r.register(new GotifyType());
		r.register(new PushoverType());
		return r;
	}

	public void register(NotifierType type) {
		types.put(type.id(), type);
	}

	public NotifierType get(String id) {
		return id == null ? null : types.get(id);
	}

	public Collection<NotifierType> all() {
		return types.values();
	}

	public List<NotifierType> available() {
		List<NotifierType> out = new ArrayList<>();
		for (NotifierType t : types.values()) {
			if (t.available()) {
				out.add(t);
			}
		}
		return out;
	}
}
