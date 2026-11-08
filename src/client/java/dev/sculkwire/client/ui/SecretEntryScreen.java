package dev.sculkwire.client.ui;

import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Enter or change one secret value (webhook URL, token, password). */
final class SecretEntryScreen extends FormScreen {
	private final FieldSpec field;
	private final Consumer<String> onSave;
	private String value;

	SecretEntryScreen(Screen parent, FieldSpec field, String current, Consumer<String> onSave) {
		super(Component.literal(field.label()), parent);
		this.field = field;
		this.onSave = onSave;
		this.value = field.kind() == FieldKind.KEY_VALUE_LIST ? FieldForm.jsonToMapText(current) : current;
	}

	@Override
	protected void buildFixed() {
		footer(button("Save", () -> {
			String v = field.kind() == FieldKind.KEY_VALUE_LIST ? FieldForm.mapToJson(value) : value.trim();
			minecraft.gui.setScreen(parent);
			onSave.accept(v);
		}), button("Paste", "Paste from the clipboard", () -> {
			value = minecraft.keyboardHandler.getClipboard().trim();
			rebuild();
		}), button("Cancel", this::onClose));
	}

	@Override
	protected void buildRows() {
		text(field.hint().isEmpty() ? "Enter the value." : field.hint(), GRAY);
		space(4);
		EditBox b = box(value, 4000, v -> value = v,
				field.kind() == FieldKind.KEY_VALUE_LIST ? "Authorization: Bearer ...; X-Other: value" : "");
		row(null).add(b, -1);
		setInitialFocus(b);
		space(4);
		text(Component.literal("Stored as plain text in config/sculkwire/secrets.json. It is never exported or logged."), DARK, 0);
	}
}
