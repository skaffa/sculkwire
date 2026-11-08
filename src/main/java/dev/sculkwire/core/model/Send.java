package dev.sculkwire.core.model;

import java.util.ArrayList;
import java.util.List;

/** "Send this message to these notifiers": the action block inside a {@link Branch}. */
public final class Send {
	public MessageTemplate message = MessageTemplate.of("", "");
	public List<String> notifiers = new ArrayList<>();

	public static Send of(MessageTemplate message, String... notifiers) {
		Send s = new Send();
		s.message = message;
		s.notifiers = new ArrayList<>(List.of(notifiers));
		return s;
	}
}
