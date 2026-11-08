package dev.sculkwire.core.notify;

/** Outcome of one delivery attempt. */
public record SendResult(boolean ok, int status, String error, long retryAfterMs, boolean retryable) {
	public static SendResult ok(int status) {
		return new SendResult(true, status, "", 0, false);
	}

	public static SendResult fail(int status, String error) {
		return new SendResult(false, status, error, 0, false);
	}

	public static SendResult retry(int status, String error, long retryAfterMs) {
		return new SendResult(false, status, error, retryAfterMs, true);
	}
}
