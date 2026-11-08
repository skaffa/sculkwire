package dev.sculkwire.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A scrollable form of rows (label + vanilla widgets, headers, wrapped text). Subclasses add rows in
 * {@link #buildRows()} and call {@link #rebuild()} when the structure changes. Widgets outside the
 * visible area are hidden, so the vanilla widget code needs no scrolling support.
 */
public abstract class FormScreen extends Screen {
	public static final int WHITE = 0xFFFFFFFF;
	public static final int GRAY = 0xFFA8A8A8;
	public static final int DARK = 0xFF707070;
	public static final int ACCENT = 0xFF4FD8E0;
	public static final int GOOD = 0xFF55DD77;
	public static final int BAD = 0xFFFF6666;
	public static final int WARN = 0xFFFFC04D;

	protected final Screen parent;
	protected final List<Row> rows = new ArrayList<>();
	protected double scroll;
	protected int top = 34;
	protected int bottom;
	protected int formLeft;
	protected int formWidth;
	protected int labelWidth = 120;
	private int contentHeight;
	private boolean built;

	/** One line of the form. */
	public static final class Row {
		int h = 20;
		int y;
		int indent;
		Component label;
		int labelColor = GRAY;
		boolean header;
		boolean visible;
		final List<AbstractWidget> widgets = new ArrayList<>();
		final List<Integer> widths = new ArrayList<>();
		List<FormattedCharSequence> lines;
		Supplier<List<Component>> live;
		int textColor = GRAY;
		Supplier<Integer> dot;

		/** Adds a widget; width -1 fills the remaining space (shared between fill widgets). */
		public Row add(AbstractWidget w, int width) {
			widgets.add(w);
			widths.add(width);
			return this;
		}

		public Row indent(int px) {
			indent = px;
			return this;
		}

		public Row color(int c) {
			labelColor = c;
			textColor = c;
			return this;
		}

		/** A colored status dot drawn before the label. */
		public Row dot(Supplier<Integer> color) {
			dot = color;
			return this;
		}
	}

	protected FormScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	protected int footerHeight() {
		return 30;
	}

	protected int headerHeight() {
		return 34;
	}

	/** Adds fixed widgets (header tabs, footer buttons). Called on every init. */
	protected void buildFixed() {
	}

	/** Adds the scrolling rows. */
	protected abstract void buildRows();

	@Override
	protected void init() {
		formWidth = Math.min(width - 28, 460);
		formLeft = (width - formWidth) / 2;
		labelWidth = Math.min(130, formWidth / 3);
		top = headerHeight();
		bottom = height - footerHeight();
		clearWidgets();
		buildFixed();
		rows.clear();
		buildRows();
		for (Row r : rows) {
			r.widgets.forEach(this::addRenderableWidget);
		}
		built = true;
		layout();
	}

	/** Rebuilds the rows (keeps fixed widgets and the scroll position). */
	protected void rebuild() {
		if (!built) {
			return;
		}
		GuiEventListener focused = getFocused();
		for (Row r : rows) {
			r.widgets.forEach(this::removeWidget);
		}
		rows.clear();
		buildRows();
		for (Row r : rows) {
			r.widgets.forEach(this::addRenderableWidget);
		}
		if (focused != null && !children().contains(focused)) {
			setFocused(null);
		}
		layout();
	}

	protected void layout() {
		int y = top + 4 - (int) scroll;
		for (Row r : rows) {
			if (r.lines != null) {
				r.h = Math.max(10, r.lines.size() * 10 + 2);
			} else if (r.live != null && r.widgets.isEmpty()) {
				List<Component> l = r.live.get();
				int n = 0;
				int avail = formWidth - r.indent - (r.label != null ? labelWidth : 0);
				for (Component c : l) {
					n += Math.max(1, font.split(c, Math.max(40, avail)).size());
				}
				r.h = Math.max(r.label != null ? 20 : 10, n * 10 + 2);
			} else if (r.header) {
				r.h = 20;
			}
			r.y = y;
			int x = formLeft + r.indent + (r.label != null ? labelWidth : 0);
			int fixedSum = 0;
			int fills = 0;
			for (int i = 0; i < r.widgets.size(); i++) {
				int w = r.widths.get(i);
				if (w < 0) {
					fills++;
				} else {
					fixedSum += w;
				}
			}
			int gaps = Math.max(0, r.widgets.size() - 1) * 4;
			int fillW = fills == 0 ? 0 : Math.max(30, (formLeft + formWidth - x - fixedSum - gaps) / fills);
			boolean vis = y >= top && y + r.h <= bottom;
			r.visible = vis;
			for (int i = 0; i < r.widgets.size(); i++) {
				AbstractWidget w = r.widgets.get(i);
				int ww = r.widths.get(i) < 0 ? fillW : r.widths.get(i);
				w.setX(x);
				w.setWidth(ww);
				w.setY(y + (r.h - w.getHeight()) / 2);
				w.visible = vis;
				x += ww + 4;
			}
			y += r.h + 3;
		}
		contentHeight = y + (int) scroll - top;
		double max = Math.max(0, contentHeight - (bottom - top));
		if (scroll > max) {
			scroll = max;
			layout();
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (my >= top && my <= bottom) {
			double max = Math.max(0, contentHeight - (bottom - top));
			double next = Math.max(0, Math.min(max, scroll - sy * 24));
			if (next != scroll) {
				scroll = next;
				layout();
			}
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, title, width / 2, 8, WHITE);
		boolean relayout = false;
		g.enableScissor(0, top, width, bottom);
		for (Row r : rows) {
			if (r.y + r.h < top || r.y > bottom || (!r.widgets.isEmpty() && !r.visible)) {
				continue;
			}
			int x = formLeft + r.indent;
			if (r.header) {
				g.text(font, r.label, x, r.y + 7, ACCENT);
				g.fill(formLeft, r.y + r.h - 2, formLeft + formWidth, r.y + r.h - 1, 0x404FD8E0);
				continue;
			}
			if (r.dot != null) {
				g.fill(x, r.y + r.h / 2 - 3, x + 6, r.y + r.h / 2 + 3, r.dot.get());
				x += 10;
			}
			if (r.label != null) {
				String s = font.plainSubstrByWidth(r.label.getString(), labelWidth - 6 - (x - formLeft - r.indent));
				g.text(font, Component.literal(s).withStyle(r.label.getStyle()), x, r.y + (r.h - 8) / 2, r.labelColor);
			}
			if (r.lines != null) {
				int ly = r.y + 1;
				for (FormattedCharSequence line : r.lines) {
					g.text(font, line, formLeft + r.indent, ly, r.textColor);
					ly += 10;
				}
			}
			if (r.live != null) {
				int lx = formLeft + r.indent + (r.label != null ? labelWidth : 0);
				int avail = Math.max(40, formLeft + formWidth - lx);
				int ly = r.y + (r.widgets.isEmpty() ? (r.label != null && r.h <= 22 ? (r.h - 8) / 2 : 1) : (r.h - 8) / 2);
				if (!r.widgets.isEmpty()) {
					AbstractWidget last = r.widgets.get(r.widgets.size() - 1);
					lx = last.getX() + last.getWidth() + 6;
					avail = Math.max(20, formLeft + formWidth - lx);
				}
				int lines = 0;
				for (Component c : r.live.get()) {
					for (FormattedCharSequence seq : font.split(c, avail)) {
						g.text(font, seq, lx, ly, r.textColor);
						ly += 10;
						lines++;
					}
				}
				if (r.widgets.isEmpty() && Math.max(r.label != null ? 20 : 10, lines * 10 + 2) != r.h) {
					relayout = true;
				}
			}
		}
		g.disableScissor();
		int view = bottom - top;
		if (contentHeight > view) {
			int barH = Math.max(16, view * view / contentHeight);
			int barY = top + (int) ((view - barH) * (scroll / Math.max(1, contentHeight - view)));
			int bx = Math.min(width - 4, formLeft + formWidth + 6);
			g.fill(bx, top, bx + 2, bottom, 0x30FFFFFF);
			g.fill(bx, barY, bx + 2, barY + barH, 0xA0FFFFFF);
		}
		extractExtra(g, mx, my);
		if (relayout) {
			layout();
		}
	}

	/** Extra drawing on top (subclasses). */
	protected void extractExtra(GuiGraphicsExtractor g, int mx, int my) {
	}

	// ---- row builders ----

	protected Row row(String label) {
		Row r = new Row();
		r.label = label == null ? null : Component.literal(label);
		rows.add(r);
		return r;
	}

	protected Row header(String text) {
		Row r = new Row();
		r.header = true;
		r.label = Component.literal(text).withStyle(ChatFormatting.BOLD);
		rows.add(r);
		return r;
	}

	protected Row text(String text, int color) {
		return text(Component.literal(text), color, 0);
	}

	protected Row text(Component text, int color, int indent) {
		Row r = new Row();
		r.indent = indent;
		r.lines = font.split(text, Math.max(40, formWidth - indent));
		r.textColor = color;
		rows.add(r);
		return r;
	}

	protected Row live(String label, Supplier<List<Component>> lines, int color) {
		Row r = row(label);
		r.live = lines;
		r.textColor = color;
		return r;
	}

	protected void space(int h) {
		Row r = new Row();
		r.h = h;
		r.lines = List.of();
		rows.add(r);
		r.h = h;
	}

	// ---- fixed widgets ----

	protected <W extends AbstractWidget> W fixed(W w, int x, int y, int width) {
		w.setX(x);
		w.setY(y);
		w.setWidth(width);
		addRenderableWidget(w);
		return w;
	}

	/** Centers buttons in the footer. */
	protected void footer(Button... buttons) {
		int bw = Math.min(110, (formWidth - (buttons.length - 1) * 6) / Math.max(1, buttons.length));
		int total = buttons.length * bw + (buttons.length - 1) * 6;
		int x = (width - total) / 2;
		for (Button b : buttons) {
			fixed(b, x, height - 25, bw);
			x += bw + 6;
		}
	}

	// ---- widget factories ----

	protected EditBox box(String value, int maxLen, Consumer<String> onChange, String hint) {
		EditBox b = new EditBox(font, 0, 0, 100, 18, Component.empty());
		b.setMaxLength(maxLen);
		b.setValue(value == null ? "" : value);
		b.moveCursorToStart(false);
		if (hint != null && !hint.isEmpty()) {
			b.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		}
		b.setResponder(onChange);
		return b;
	}

	protected Button button(String label, Runnable onPress) {
		return Button.builder(Component.literal(label), b -> onPress.run()).bounds(0, 0, 60, 20).build();
	}

	protected Button button(String label, String tooltip, Runnable onPress) {
		Button b = button(label, onPress);
		if (tooltip != null && !tooltip.isEmpty()) {
			b.setTooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return b;
	}

	protected <T> Button cycle(List<T> options, Supplier<T> get, Consumer<T> set, Function<T, String> display) {
		return Button.builder(Component.literal(display.apply(get.get())), b -> {
			int i = options.indexOf(get.get());
			T next = options.get((i + 1) % options.size());
			set.accept(next);
			b.setMessage(Component.literal(display.apply(next)));
		}).bounds(0, 0, 60, 20).build();
	}

	protected Button toggle(Supplier<Boolean> get, Consumer<Boolean> set, String on, String off) {
		return Button.builder(Component.literal(get.get() ? on : off), b -> {
			boolean next = !get.get();
			set.accept(next);
			b.setMessage(Component.literal(next ? on : off));
		}).bounds(0, 0, 60, 20).build();
	}

	protected static double parseNumber(String s, double def) {
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** Editing helper: shows newlines as \n in single-line boxes. */
	protected static String escapeNewlines(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\n", "\\n");
	}

	protected static String unescapeNewlines(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(i + 1);
				if (n == 'n') {
					sb.append('\n');
					i++;
					continue;
				}
				if (n == '\\') {
					sb.append('\\');
					i++;
					continue;
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}
}
