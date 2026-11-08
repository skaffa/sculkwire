package dev.sculkwire.core.field;

/** Input widget kinds for generated forms (notifier settings, event parameters, signal parameters). */
public enum FieldKind {
	TEXT,
	MULTILINE,
	URL,
	SECRET,
	INT,
	NUMBER,
	BOOL,
	ENUM,
	/** List of key: value pairs, e.g. HTTP headers. Stored as a JSON object. */
	KEY_VALUE_LIST,
	/** List of strings. Stored as a JSON array. */
	STRING_LIST,
	COLOR,
	/** A resource id with registry autocomplete. */
	ID,
	/** A list of resource ids (or tags). */
	ID_LIST,
	REGEX,
	/** Text with {variables}. */
	TEMPLATE
}
