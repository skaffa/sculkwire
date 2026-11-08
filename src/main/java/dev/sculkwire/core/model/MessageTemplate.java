package dev.sculkwire.core.model;

import java.util.ArrayList;
import java.util.List;

/** Message text with {variables}. In a target override, null fields inherit from the rule's message. */
public final class MessageTemplate {
	public String title;
	public String body;
	/** min, low, default, high, urgent */
	public String priority;
	public List<String> tags;
	public String click;

	public static MessageTemplate of(String title, String body) {
		MessageTemplate m = new MessageTemplate();
		m.title = title;
		m.body = body;
		m.priority = "default";
		m.tags = new ArrayList<>();
		m.click = "";
		return m;
	}

	/** This message with the non-null fields of {@code override} replacing its own. */
	public MessageTemplate merge(MessageTemplate override) {
		MessageTemplate m = new MessageTemplate();
		m.title = pick(override == null ? null : override.title, title);
		m.body = pick(override == null ? null : override.body, body);
		m.priority = pick(override == null ? null : override.priority, priority);
		m.click = pick(override == null ? null : override.click, click);
		m.tags = override != null && override.tags != null ? override.tags : tags;
		return m;
	}

	private static String pick(String a, String b) {
		return a != null ? a : b;
	}
}
