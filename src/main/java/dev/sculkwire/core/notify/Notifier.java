package dev.sculkwire.core.notify;

import dev.sculkwire.core.template.Template;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A configured, ready-to-use notifier instance. {@link #send} is called on the dispatch thread. */
public interface Notifier {
	CompletableFuture<SendResult> send(Notification n);

	/** Templates in this notifier's own settings (e.g. a webhook body) whose values must be captured. */
	default List<Template> templates() {
		return List.of();
	}
}
