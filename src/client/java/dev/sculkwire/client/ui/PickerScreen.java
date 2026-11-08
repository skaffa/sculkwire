package dev.sculkwire.client.ui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** A searchable list; picking an item returns to the parent and calls the callback. */
public final class PickerScreen<T> extends FormScreen {
	public record Item<T>(String label, String detail, String section, T value) {
	}

	private final List<Item<T>> items;
	private final Consumer<T> onPick;
	private String filter = "";
	private EditBox search;

	public PickerScreen(Screen parent, String title, List<Item<T>> items, Consumer<T> onPick) {
		super(Component.literal(title), parent);
		this.items = items;
		this.onPick = onPick;
	}

	@Override
	protected int headerHeight() {
		return 46;
	}

	@Override
	protected void buildFixed() {
		search = box(filter, 100, s -> {
			filter = s;
			scroll = 0;
			rebuild();
		}, "Search…");
		fixed(search, formLeft, 22, formWidth);
		setInitialFocus(search);
		footer(button("Cancel", this::onClose));
	}

	@Override
	protected void buildRows() {
		String f = filter.toLowerCase(Locale.ROOT).trim();
		String section = null;
		int shown = 0;
		for (Item<T> it : items) {
			if (!f.isEmpty() && !(it.label().toLowerCase(Locale.ROOT).contains(f) || it.detail().toLowerCase(Locale.ROOT).contains(f)
					|| it.section().toLowerCase(Locale.ROOT).contains(f))) {
				continue;
			}
			if (!it.section().equals(section)) {
				section = it.section();
				if (!section.isEmpty()) {
					header(section);
				}
			}
			int bw = Math.min(200, formWidth / 2);
			row(null).add(button(it.label(), it.detail(), () -> {
				minecraft.gui.setScreen(parent);
				onPick.accept(it.value());
			}), bw).live = () -> List.of(Component.literal(it.detail()));
			rows.get(rows.size() - 1).textColor = GRAY;
			shown++;
		}
		if (shown == 0) {
			text("Nothing matches.", DARK);
		}
	}
}
