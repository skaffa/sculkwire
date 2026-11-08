package dev.sculkwire.core.model;

import com.google.gson.JsonObject;

/** A configured notifier instance. Secret fields live in secrets.json, not here. */
public final class NotifierConfig {
	public String id = "";
	public String name = "";
	public String type = "";
	public boolean enabled = true;
	public JsonObject settings = new JsonObject();

	public static NotifierConfig of(String id, String name, String type) {
		NotifierConfig n = new NotifierConfig();
		n.id = id;
		n.name = name;
		n.type = type;
		return n;
	}

	public String displayName() {
		return name == null || name.isBlank() ? id : name;
	}
}
