package dev.sculkwire.core.template;

/** Where a rendered template goes; substituted values (never the template's own text) are escaped for it. */
public enum OutputContext {
	PLAIN,
	/** Inside a JSON string literal written by the user, e.g. {@code {"text": "{msg.text}"}}. */
	JSON_STRING,
	/** A URL path or query component. */
	URL_COMPONENT,
	/** application/x-www-form-urlencoded value. */
	FORM_COMPONENT,
	/** An HTTP header value: no line breaks or control characters. */
	HEADER,
	/** Discord message text: markdown and mass mentions neutralised. */
	DISCORD_MARKDOWN,
	/** Telegram HTML parse mode. */
	TELEGRAM_HTML,
	/** Inside a regular expression: values match literally. */
	REGEX
}
