package dev.sculkwire.core.notify;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Minimal async HTTP abstraction so notifiers can be tested without a network. */
public interface HttpTransport {
	CompletableFuture<HttpResult> send(HttpCall call);

	record HttpCall(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {
	}

	record HttpResult(int status, Map<String, List<String>> headers, String body) {
		public String header(String name) {
			if (headers == null) {
				return null;
			}
			for (Map.Entry<String, List<String>> e : headers.entrySet()) {
				if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))
						&& !e.getValue().isEmpty()) {
					return e.getValue().get(0);
				}
			}
			return null;
		}
	}
}
