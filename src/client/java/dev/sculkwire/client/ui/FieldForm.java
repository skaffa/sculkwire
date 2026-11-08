package dev.sculkwire.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.util.Redact;
import dev.sculkwire.core.value.Value;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a list of {@link FieldSpec}s as form rows bound to a JSON object (and a secret store). */
final class FieldForm {
	/** Pending secret values for one notifier. */
	interface SecretStore {
		String get(String key);

		void set(String key, String value);
	}

	private FieldForm() {
	}

	static void build(FormScreen s, List<FieldSpec> fields, JsonObject target, SecretStore secrets, Runnable onChange) {
		for (FieldSpec f : fields) {
			String label = f.label() + (f.required() ? " *" : "");
			if (f.secret() || f.kind() == FieldKind.SECRET) {
				FormScreen.Row r = s.row(label);
				r.add(s.button("Set…", "Enter " + f.label() + " (stored in secrets.json)", () ->
						net.minecraft.client.Minecraft.getInstance().gui.setScreen(new SecretEntryScreen(s, f, secrets == null ? "" : secrets.get(f.key()), v -> {
							if (secrets != null) {
								secrets.set(f.key(), v);
							}
							onChange.run();
						}))), 50);
				r.add(s.button("Clear", () -> {
					if (secrets != null) {
						secrets.set(f.key(), "");
					}
					onChange.run();
				}), 44);
				r.live = () -> {
					String v = secrets == null ? "" : secrets.get(f.key());
					return List.of(Component.literal(v.isEmpty() ? "not set" : f.kind() == FieldKind.KEY_VALUE_LIST
							? v.length() + " characters" : Redact.mask(v)));
				};
				r.textColor = FormScreen.GRAY;
				continue;
			}
			FormScreen.Row r = s.row(label);
			AbstractWidget w = widget(s, f, target, onChange);
			if (!f.hint().isEmpty()) {
				w.setTooltip(Tooltip.create(Component.literal(f.hint())));
			}
			r.add(w, f.kind() == FieldKind.BOOL || f.kind() == FieldKind.ENUM ? Math.min(160, s.formWidth - s.labelWidth) : -1);
		}
	}

	private static AbstractWidget widget(FormScreen s, FieldSpec f, JsonObject target, Runnable onChange) {
		switch (f.kind()) {
			case BOOL:
				return s.toggle(() -> f.bool(target), v -> {
					target.addProperty(f.key(), v);
					onChange.run();
				}, "Yes", "No");
			case ENUM: {
				List<String> opts = f.options().isEmpty() ? List.of("") : f.options();
				return s.cycle(opts, () -> {
					String v = f.string(target);
					return opts.contains(v) ? v : opts.get(0);
				}, v -> {
					target.addProperty(f.key(), v);
					onChange.run();
				}, v -> v);
			}
			case INT:
			case NUMBER: {
				JsonElement raw = f.raw(target);
				String text = raw == null || raw.isJsonNull() ? "" : Value.fromJson(raw).asString();
				return s.box(text, 30, v -> {
					if (v.isBlank()) {
						target.remove(f.key());
					} else {
						double d = FormScreen.parseNumber(v, Double.NaN);
						if (!Double.isNaN(d)) {
							target.addProperty(f.key(), d);
						}
					}
					onChange.run();
				}, f.hint());
			}
			case STRING_LIST:
			case ID_LIST:
				return s.box(String.join(", ", f.list(target)), 4000, v -> {
					JsonArray a = new JsonArray();
					Value.splitList(v).forEach(a::add);
					target.add(f.key(), a);
					onChange.run();
				}, f.hint());
			case KEY_VALUE_LIST: {
				Map<String, String> m = f.map(target);
				return s.box(formatMap(m), 4000, v -> {
					target.add(f.key(), parseMap(v));
					onChange.run();
				}, "Key: value; Key2: value2");
			}
			case MULTILINE:
			case TEMPLATE:
				return s.box(FormScreen.escapeNewlines(f.string(target)), 10000, v -> {
					target.addProperty(f.key(), FormScreen.unescapeNewlines(v));
					onChange.run();
				}, f.hint());
			default: {
				EditBox b = s.box(f.string(target), 2000, v -> {
					target.addProperty(f.key(), v);
					onChange.run();
				}, f.hint());
				return b;
			}
		}
	}

	static String formatMap(Map<String, String> m) {
		List<String> parts = new ArrayList<>();
		m.forEach((k, v) -> parts.add(k + ": " + v));
		return String.join("; ", parts);
	}

	static JsonObject parseMap(String text) {
		JsonObject o = new JsonObject();
		for (String part : text.split(";")) {
			int c = part.indexOf(':');
			if (c > 0) {
				String k = part.substring(0, c).trim();
				if (!k.isEmpty()) {
					o.addProperty(k, part.substring(c + 1).trim());
				}
			}
		}
		return o;
	}

	/** Secret key-value lists are stored as a JSON object string. */
	static String mapToJson(String text) {
		return parseMap(text).toString();
	}

	static String jsonToMapText(String json) {
		if (json == null || json.isBlank()) {
			return "";
		}
		try {
			JsonElement e = JsonParser.parseString(json);
			if (e.isJsonObject()) {
				Map<String, String> m = new LinkedHashMap<>();
				e.getAsJsonObject().entrySet().forEach(en -> m.put(en.getKey(), en.getValue().getAsString()));
				return formatMap(m);
			}
		} catch (RuntimeException ignored) {
			// fall through
		}
		return json;
	}
}
