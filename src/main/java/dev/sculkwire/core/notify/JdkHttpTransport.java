package dev.sculkwire.core.notify;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** {@link HttpTransport} on the JDK HTTP client. Redirects are never followed. */
public final class JdkHttpTransport implements HttpTransport {
	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	@Override
	public CompletableFuture<HttpResult> send(HttpCall call) {
		HttpRequest.BodyPublisher body = call.body() == null || call.body().length == 0
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofByteArray(call.body());
		HttpRequest.Builder b = HttpRequest.newBuilder(call.uri())
				.timeout(call.timeout() == null ? Duration.ofSeconds(15) : call.timeout())
				.method(call.method(), body)
				.header("User-Agent", "Sculkwire (Minecraft mod)");
		for (Map.Entry<String, String> h : call.headers().entrySet()) {
			b.header(h.getKey(), h.getValue());
		}
		return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(r -> new HttpResult(r.statusCode(), r.headers().map(), r.body()));
	}
}
