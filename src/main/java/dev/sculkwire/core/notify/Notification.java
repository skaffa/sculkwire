package dev.sculkwire.core.notify;

import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Snapshot;
import dev.sculkwire.core.template.Template;

import java.util.List;
import java.util.Map;

/**
 * A fired notification: message templates plus the values captured when the rule fired. Notifiers
 * render the templates with their own escaping, on the dispatch thread.
 */
public record Notification(Template title, Template body, Priority priority, List<String> tags, Template click,
		Snapshot vars, String ruleId, String ruleName, boolean test, long createdAt) {

	public String title(OutputContext ctx) {
		return title.render(vars, ctx);
	}

	public String body(OutputContext ctx) {
		return body.render(vars, ctx);
	}

	public String click(OutputContext ctx) {
		return click.render(vars, ctx);
	}

	/** Renders another template (e.g. a webhook body) with this notification's values. */
	public String render(Template t, OutputContext ctx) {
		return t.render(vars, ctx);
	}

	/** A notification with fixed text, e.g. for the notifier Test button. */
	public static Notification simple(String title, String body, Priority priority, boolean test) {
		return new Notification(Template.literal(title), Template.literal(body), priority, List.of(), Template.EMPTY,
				Snapshot.of(Map.of()), "", "", test, System.currentTimeMillis());
	}
}
