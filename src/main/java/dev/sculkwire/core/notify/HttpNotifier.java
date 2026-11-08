package dev.sculkwire.core.notify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Shared HTTP plumbing and response interpretation for notifiers. */
public abstract class HttpNotifier implements Notifier {
	protected final HttpTransport http;

	protected HttpNotifier(HttpTransport http) {
		this.http = http;
	}

	protected CompletableFuture<SendResult> call(String method, String url, Map<String, String> headers, String body,
			Duration timeout) {
		URI uri;
		try {
			uri = URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			return CompletableFuture.completedFuture(SendResult.fail(0, "Invalid URL"));
		}
		byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
		try {
			return http.send(new HttpTransport.HttpCall(method, uri, headers, bytes, timeout))
					.handle((r, ex) -> ex != null ? fromException(ex) : interpret(r));
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(fromException(e));
		}
	}

	/** 2xx ok; 429 retry after the advertised delay; 5xx retry; other 4xx fail. */
	public static SendResult interpret(HttpTransport.HttpResult r) {
		int s = r.status();
		if (s >= 200 && s < 300) {
			return SendResult.ok(s);
		}
		if (s == 429) {
			return SendResult.retry(s, "Rate limited", retryAfterMs(r));
		}
		if (s >= 500) {
			return SendResult.retry(s, "Server error " + s + snippet(errorText(r.body())), 0);
		}
		return SendResult.fail(s, "HTTP " + s + snippet(errorText(r.body())));
	}

	public static SendResult fromException(Throwable t) {
		Throwable e = t;
		while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
			e = e.getCause();
		}
		if (e instanceof HttpTimeoutException) {
			return SendResult.retry(0, "Timed out", 0);
		}
		if (e instanceof IOException) {
			String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			return SendResult.retry(0, "Could not connect: " + m, 0);
		}
		return SendResult.fail(0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
	}

	static long retryAfterMs(HttpTransport.HttpResult r) {
		String h = r.header("Retry-After");
		if (h != null) {
			try {
				return (long) (Double.parseDouble(h.trim()) * 1000);
			} catch (NumberFormatException ignored) {
				// HTTP date form: fall through
			}
		}
		try {
			JsonElement e = JsonParser.parseString(r.body() == null ? "" : r.body());
			if (e.isJsonObject() && e.getAsJsonObject().has("retry_after")) {
				return (long) (e.getAsJsonObject().get("retry_after").getAsDouble() * 1000);
			}
		} catch (RuntimeException ignored) {
			// not JSON
		}
		return 5000;
	}

	/** Pulls a message out of common JSON error shapes, or returns the raw body. */
	static String errorText(String body) {
		if (body == null) {
			return "";
		}
		try {
			JsonElement e = JsonParser.parseString(body);
			if (e.isJsonObject()) {
				JsonObject o = e.getAsJsonObject();
				for (String k : new String[] {"message", "error", "description"}) {
					if (o.has(k) && o.get(k).isJsonPrimitive()) {
						return o.get(k).getAsString();
					}
				}
			}
		} catch (RuntimeException ignored) {
			// not JSON
		}
		return body;
	}

	static String snippet(String s) {
		if (s == null || s.isBlank()) {
			return "";
		}
		String t = s.strip().replaceAll("\\s+", " ");
		return ": " + (t.length() > 160 ? t.substring(0, 160) + "…" : t);
	}

	/** Truncates to at most {@code maxBytes} UTF-8 bytes, adding an ellipsis. */
	public static String truncateBytes(String s, int maxBytes) {
		if (s == null) {
			return "";
		}
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		if (b.length <= maxBytes) {
			return s;
		}
		int chars = s.length();
		while (chars > 0 && (s.substring(0, chars) + "…").getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			chars = Math.max(0, chars - Math.max(1, (chars / 10)));
		}
		while (chars < s.length() && (s.substring(0, chars + 1) + "…").getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
			chars++;
		}
		return s.substring(0, chars) + "…";
	}

	public static String truncate(String s, int maxChars) {
		if (s == null) {
			return "";
		}
		return s.length() <= maxChars ? s : s.substring(0, Math.max(0, maxChars - 1)) + "…";
	}
}
