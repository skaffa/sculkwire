package dev.sculkwire.core.notify;

/** Read access to secret notifier settings. */
@FunctionalInterface
public interface SecretSource {
	SecretSource NONE = (id, key) -> "";

	String get(String notifierId, String key);
}
