package dev.sculkwire.core.model;

/** A notifier a rule sends to, with an optional message override. */
public final class Target {
	public String notifier;
	public MessageTemplate override;

	public static Target of(String notifierId) {
		Target t = new Target();
		t.notifier = notifierId;
		return t;
	}
}
