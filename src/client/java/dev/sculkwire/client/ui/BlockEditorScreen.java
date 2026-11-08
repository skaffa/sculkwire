package dev.sculkwire.client.ui;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.core.engine.CompiledRule;
import dev.sculkwire.core.event.EventSpec;
import dev.sculkwire.core.model.Branch;
import dev.sculkwire.core.model.Condition;
import dev.sculkwire.core.model.MessageTemplate;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Operand;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Send;
import dev.sculkwire.core.model.Target;
import dev.sculkwire.core.model.Trigger;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.signal.SignalRef;
import dev.sculkwire.core.signal.SignalSpec;
import dev.sculkwire.core.util.Json;
import dev.sculkwire.core.value.Op;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The event editor as Scratch-like blocks: a "when" hat (the trigger), then an if / else-if / else chain
 * whose branches hold "send" blocks. Conditions are nested and/or/not blocks around comparisons.
 * Drag blocks (and live values) from the palette; click a block to edit it; right-click or drag it back
 * to the palette to delete it.
 */
public final class BlockEditorScreen extends Screen {
	private static final int HAT = 0xFFE6A800;
	private static final int HAT_B = 0xFFA87B00;
	private static final int CTRL = 0xFFFF9C1A;
	private static final int CTRL_B = 0xFFC2730F;
	private static final int SEND = 0xFF8F5CF0;
	private static final int SEND_B = 0xFF6640B8;
	private static final int LOGIC = 0xFF4CB34C;
	private static final int LOGIC_B = 0xFF2F822F;
	private static final int SENSE = 0xFF3E9FCC;
	private static final int SENSE_B = 0xFF23739A;
	private static final int SLOT_FILL = 0x66000000;
	private static final int SLOT_B = 0xCCFFFFFF;
	private static final int PANEL = 0xB0101418;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFA0A0A0;
	private static final int DROP = 0xFFFFFFFF;

	private enum PKind {
		IF, ELSE, SEND, AND, OR, NOT, COMPARE, MOVE_BRANCH, MOVE_SEND, MOVE_COND
	}

	private record Payload(PKind kind, Object obj, String label, int color) {
		boolean isBranch() {
			return kind == PKind.IF || kind == PKind.ELSE || kind == PKind.MOVE_BRANCH;
		}

		boolean isSend() {
			return kind == PKind.SEND || kind == PKind.MOVE_SEND;
		}

		boolean isCond() {
			return kind == PKind.AND || kind == PKind.OR || kind == PKind.NOT || kind == PKind.COMPARE || kind == PKind.MOVE_COND;
		}
	}

	/** Where a condition lives, so it can be replaced. */
	private interface Holder {
		Condition get();

		void set(Condition c);

		/** The group this condition is a child of (null for a branch condition). */
		default Condition group() {
			return null;
		}
	}

	private enum HKind {
		HAT, BRANCH, SEND, COND, SLOT, GROUP_LABEL, BRANCH_DROP, SEND_DROP, GROUP_ADD, EMPTY_CANVAS
	}

	private record Hit(HKind kind, int x, int y, int w, int h, Branch branch, int index, Send send, Holder holder) {
		boolean contains(double px, double py, int pad) {
			return px >= x - pad && px < x + w + pad && py >= y - pad && py < y + h + pad;
		}
	}

	private record PalItem(String label, String value, int color, Payload payload, String hint) {
		boolean header() {
			return payload == null;
		}
	}

	private final Screen parent;
	private final ClientRuntime rt = ClientRuntime.get();
	private final boolean isNew;
	private final String originalId;
	private Rule rule;
	private CompiledRule compiled;
	private int liveBranch = -1;
	private final Deque<String> undo = new ArrayDeque<>();

	private int palW;
	private int top;
	private int bottom;
	private int canvasX;
	private double scrollY;
	private double scrollX;
	private double palScroll;
	private int contentH;
	private int contentW;
	private int palContentH;
	private EditBox search;
	private String filter = "";
	private final List<Hit> hits = new ArrayList<>();
	private final List<PalItem> palette = new ArrayList<>();
	private final List<int[]> palPos = new ArrayList<>();

	private Payload pressed;
	private Hit pressedHit;
	private double pressX;
	private double pressY;
	private boolean dragging;
	private Runnable restore;
	private int pressButton;

	public BlockEditorScreen(Screen parent, Rule rule, boolean isNew) {
		super(Component.literal(isNew ? "New event" : "Edit event"));
		this.parent = parent;
		this.rule = rule;
		this.isNew = isNew;
		this.originalId = rule.id;
		normalize();
		recompile();
	}

	/** An example with if / else-if / else, for screenshots. */
	public static Rule demoRule() {
		Rule r = new Rule();
		r.name = "Health check while away";
		r.trigger = Trigger.event("player.damaged");
		String n = ClientRuntime.get().config().notifiers.isEmpty() ? "phone" : ClientRuntime.get().config().notifiers.get(0).id;
		Condition notPlaying = Condition.group("none", Condition.compare(Operand.signal("session.actively_playing"), "is_true"));
		Condition low = Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6));
		r.branches = new ArrayList<>(List.of(
				Branch.of(Condition.group("all", low, notPlaying),
						Send.of(MessageTemplate.of("Low health while away!", "{player.health|hearts} hearts"), n)),
				Branch.of(Condition.group("any", Condition.compare(Operand.payload("damage.source"), "contains", Operand.value("lava")),
						Condition.compare(Operand.signal("player.on_fire"), "is_true")),
						Send.of(MessageTemplate.of("You are burning", "{damage.source}"), n)),
				Branch.of(null, Send.of(MessageTemplate.of("Took {damage.amount} damage", ""), n))));
		return r;
	}

	/** Puts the rule in block form: legacy message + targets become one "always" branch. */
	private void normalize() {
		if (rule.trigger == null) {
			rule.trigger = new Trigger();
		}
		if (rule.hasBranches()) {
			return;
		}
		Send s = new Send();
		s.message = rule.message == null ? MessageTemplate.of("", "") : Json.copy(rule.message, MessageTemplate.class);
		for (Target t : rule.targets) {
			if (!s.notifiers.contains(t.notifier)) {
				s.notifiers.add(t.notifier);
			}
		}
		rule.branches = new ArrayList<>();
		rule.branches.add(Branch.of(null, s));
	}

	private void recompile() {
		compiled = rt.engine.compiler().compile(rule, rt.config());
		liveBranch = -1;
		if (compiled.errors.isEmpty()) {
			try {
				liveBranch = rt.engine.previewBranch(compiled);
			} catch (RuntimeException ignored) {
				// preview is best effort
			}
		}
	}

	private void pushUndo() {
		undo.push(Json.COMPACT.toJson(rule));
		while (undo.size() > 40) {
			undo.removeLast();
		}
	}

	private void doUndo() {
		if (undo.isEmpty()) {
			return;
		}
		rule = Json.COMPACT.fromJson(undo.pop(), Rule.class);
		recompile();
	}

	private void mutated() {
		recompile();
	}

	// ---- screen setup ----

	@Override
	protected void init() {
		palW = Math.max(120, Math.min(170, width / 3));
		top = 44;
		bottom = height - 40;
		canvasX = palW + 6;
		recompile();
		addRenderableWidget(btn("⚙ Settings", "Name, limits and where this event applies",
				() -> minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.SETTINGS, null)), width - 236, 20, 80));
		addRenderableWidget(btn("Undo", "Ctrl+Z", this::doUndo, width - 152, 20, 46));
		addRenderableWidget(btn("Test send", "Send now with current values (marked as test)", this::test, width - 102, 20, 96));
		search = new EditBox(font, 4, 20, palW - 4, 18, Component.empty());
		search.setMaxLength(60);
		search.setValue(filter);
		search.setHint(Component.literal("Search values…").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		search.setResponder(s -> {
			filter = s;
			palScroll = 0;
			buildPalette();
		});
		addRenderableWidget(search);
		int bw = 90;
		addRenderableWidget(btn("Save", null, this::save, width / 2 - bw - 3, height - 24, bw));
		addRenderableWidget(btn("Cancel", null, this::onClose, width / 2 + 3, height - 24, bw));
		buildPalette();
	}

	private Button btn(String label, String tip, Runnable r, int x, int y, int w) {
		Button b = Button.builder(Component.literal(label), x2 -> r.run()).bounds(x, y, w, 20).build();
		if (tip != null) {
			b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(tip)));
		}
		return b;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ---- palette ----

	private void buildPalette() {
		palette.clear();
		String f = filter.toLowerCase(Locale.ROOT).trim();
		if (f.isEmpty()) {
			palette.add(new PalItem("Blocks", "", 0, null, null));
			palette.add(new PalItem("if … then", "", CTRL, new Payload(PKind.IF, null, "if … then", CTRL), "Runs its sends when the condition is true"));
			palette.add(new PalItem("else", "", CTRL, new Payload(PKind.ELSE, null, "else", CTRL), "Runs when no 'if' above matched"));
			palette.add(new PalItem("send message", "", SEND, new Payload(PKind.SEND, null, "send message", SEND), "Send a message to notifiers"));
			palette.add(new PalItem("… and …", "", LOGIC, new Payload(PKind.AND, null, "and", LOGIC), "Both must be true"));
			palette.add(new PalItem("… or …", "", LOGIC, new Payload(PKind.OR, null, "or", LOGIC), "One must be true"));
			palette.add(new PalItem("not …", "", LOGIC, new Payload(PKind.NOT, null, "not", LOGIC), "Must be false"));
			palette.add(new PalItem("Common", "", 0, null, null));
			for (String id : List.of("session.actively_playing", "session.afk_seconds", "player.health", "world.is_night",
					"nearby.hostiles", "player.dimension", "world.weather")) {
				addSignal(id);
			}
		}
		Map<String, ValueType> payload = triggerValues();
		boolean first = true;
		for (Map.Entry<String, ValueType> e : payload.entrySet()) {
			if (match(f, e.getKey(), "")) {
				if (first) {
					palette.add(new PalItem("This trigger", "", 0, null, null));
					first = false;
				}
				palette.add(new PalItem(e.getKey(), "", SENSE, new Payload(PKind.COMPARE, Operand.payload(e.getKey()), e.getKey(), SENSE),
						"A value of this trigger (" + e.getValue().id() + ")"));
			}
		}
		first = true;
		for (UserVariable v : rt.config().variables) {
			if (match(f, v.name, "")) {
				if (first) {
					palette.add(new PalItem("Your variables", "", 0, null, null));
					first = false;
				}
				palette.add(new PalItem(v.name, "", SENSE, new Payload(PKind.COMPARE, Operand.var(v.name), v.name, SENSE), "Your variable"));
			}
		}
		String cat = null;
		for (SignalSpec s : rt.signals.specs()) {
			if (!match(f, s.id(), s.description())) {
				continue;
			}
			if (!s.category().equals(cat)) {
				cat = s.category();
				palette.add(new PalItem(RuleEditScreen.cap(cat), "", 0, null, null));
			}
			addSignal(s.id());
		}
		layoutPalette();
	}

	private void addSignal(String id) {
		SignalSpec s = rt.signals.spec(id);
		if (s != null) {
			palette.add(new PalItem(s.id(), "live", SENSE, new Payload(PKind.COMPARE, Operand.signal(s.id()), s.id(), SENSE), s.description()));
		}
	}

	private static boolean match(String f, String a, String b) {
		return f.isEmpty() || a.toLowerCase(Locale.ROOT).contains(f) || b.toLowerCase(Locale.ROOT).contains(f);
	}

	private void layoutPalette() {
		palPos.clear();
		int y = 0;
		for (PalItem it : palette) {
			int h = it.header() ? 13 : 15;
			palPos.add(new int[] {y, h});
			y += h + (it.header() ? 1 : 2);
		}
		palContentH = y;
	}

	private Map<String, ValueType> triggerValues() {
		Map<String, ValueType> m = new LinkedHashMap<>();
		if (Trigger.EVENT.equals(rule.trigger.kind)) {
			EventSpec spec = rt.events.get(rule.trigger.event);
			if (spec != null) {
				spec.payload().forEach(v -> m.put(v.name(), v.type()));
			}
		} else if (Trigger.CHANGE.equals(rule.trigger.kind)) {
			m.put("old", ValueType.NONE);
			m.put("new", ValueType.NONE);
		}
		return m;
	}

	// ---- rendering ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		String name = rule.name == null || rule.name.isBlank() ? "(unnamed event)" : rule.name;
		g.text(font, (isNew ? "New event: " : "Event: ") + name, canvasX, 6, TEXT, true);

		// panels
		g.fill(0, top, palW + 2, bottom, PANEL);
		g.fill(canvasX - 2, top, width, bottom, 0x70000000);

		drawPalette(g, mx, my);

		g.enableScissor(canvasX, top, width, bottom);
		hits.clear();
		drawProgram(g);
		if (dragging && pressed != null) {
			Hit target = findDrop(pressed, mx, my);
			if (target != null) {
				outline(g, target.x(), target.y(), target.w(), target.h(), DROP);
			}
		}
		g.disableScissor();

		drawProblems(g);

		if (dragging && pressed != null) {
			int w = font.width(pressed.label()) + 14;
			int x = mx - 6;
			int y = my - 7;
			if (pressed.isCond()) {
				hexBlock(g, x, y, w, 14, pressed.color(), darker(pressed.color()));
			} else {
				block(g, x, y, w, 16, pressed.color(), darker(pressed.color()));
			}
			g.text(font, pressed.label(), x + 7, y + 3, TEXT, true);
		}
	}

	private void drawPalette(GuiGraphicsExtractor g, int mx, int my) {
		int x = 4;
		int y0 = top + 4 - (int) palScroll;
		g.enableScissor(0, top, palW + 2, bottom);
		for (int i = 0; i < palette.size(); i++) {
			PalItem it = palette.get(i);
			int y = y0 + palPos.get(i)[0];
			int h = palPos.get(i)[1];
			if (y + h < top || y > bottom) {
				continue;
			}
			if (it.header()) {
				g.text(font, it.label(), x, y + 3, 0xFF4FD8E0, false);
				continue;
			}
			int w = palW - 8;
			if (it.payload().isCond()) {
				hexBlock(g, x, y, w, h, it.color(), darker(it.color()));
			} else {
				block(g, x, y, w, h, it.color(), darker(it.color()));
			}
			String label = it.label();
			String val = "";
			if ("live".equals(it.value())) {
				Value v = rt.engine.reader().read(it.label(), Map.of());
				val = v.isNone() ? "–" : v.asString();
			}
			int valW = val.isEmpty() ? 0 : Math.min(font.width(val), w / 3);
			String shown = font.plainSubstrByWidth(label, w - 12 - valW - (valW > 0 ? 4 : 0));
			g.text(font, shown, x + 6, y + 4, TEXT, true);
			if (!val.isEmpty()) {
				String v2 = font.plainSubstrByWidth(val, valW);
				g.text(font, v2, x + w - 6 - font.width(v2), y + 4, 0xFFE0F6FF, false);
			}
		}
		g.disableScissor();
		int view = bottom - top;
		if (palContentH > view) {
			int barH = Math.max(12, view * view / palContentH);
			int barY = top + (int) ((view - barH) * (palScroll / Math.max(1, palContentH - view)));
			g.fill(palW, barY, palW + 2, barY + barH, 0xA0FFFFFF);
		}
		// tooltip for the hovered palette item
		PalItem hover = paletteAt(mx, my);
		if (hover != null && hover.hint() != null && !dragging) {
			lastHint = hover.hint();
		}
	}

	private String lastHint = "";

	private void drawProblems(GuiGraphicsExtractor g) {
		String line;
		int color;
		if (!compiled.errors.isEmpty()) {
			line = "✖ " + compiled.errors.get(0);
			color = 0xFFFF6666;
		} else if (!compiled.warnings.isEmpty()) {
			line = "⚠ " + compiled.warnings.get(0);
			color = 0xFFFFC04D;
		} else {
			line = "✔ Ready" + (liveBranch >= 0 ? " — right now the highlighted block would run" : "");
			color = 0xFF55DD77;
		}
		int y = bottom + 3;
		g.text(font, font.plainSubstrByWidth(line, width - canvasX - 4), canvasX, y, color, false);
		String hint = lastHint.isEmpty() ? "Drag in · click: edit · right-click: delete" : lastHint;
		g.text(font, font.plainSubstrByWidth(hint, palW), 4, y, DIM, false);
		lastHint = "";
	}

	private void drawProgram(GuiGraphicsExtractor g) {
		int x0 = canvasX + 8 - (int) scrollX;
		int y = top + 8 - (int) scrollY;
		int startY = y;
		int maxW = 0;

		// hat
		String when = "when " + triggerText();
		String onlyIf = rule.conditions != null ? "only if " + rule.conditions.describe() : null;
		int hatW = Math.max(150, Math.max(font.width(when), onlyIf == null ? 0 : font.width(onlyIf)) + 18);
		int hatH = onlyIf == null ? 22 : 32;
		block(g, x0, y, hatW, hatH, HAT, HAT_B);
		g.fill(x0, y - 3, x0 + 60, y, HAT);
		g.text(font, when, x0 + 8, y + 7, TEXT, true);
		if (onlyIf != null) {
			g.text(font, onlyIf, x0 + 8, y + 19, 0xFFFFF2C0, false);
		}
		hits.add(new Hit(HKind.HAT, x0, y, hatW, hatH, null, -1, null, null));
		maxW = Math.max(maxW, hatW);
		y += hatH + 2;

		List<Branch> branches = rule.branches == null ? List.of() : rule.branches;
		for (int i = 0; i < branches.size(); i++) {
			Branch b = branches.get(i);
			hits.add(new Hit(HKind.BRANCH_DROP, x0, y - 4, 200, 8, null, i, null, null));
			String label = labelFor(i);
			int labelW = font.width(label);
			int[] expr = b.when == null ? new int[] {0, 0} : measure(b.when);
			int headerH = Math.max(20, expr[1] + 6);
			int headerW = b.when == null ? labelW + 20 : 8 + labelW + 6 + expr[0] + 6 + font.width("then") + 10;
			List<int[]> sendSizes = new ArrayList<>();
			int bodyH = 4;
			int maxSendW = 0;
			for (Send s : b.sends) {
				int sw = font.width(sendText(s)) + 16;
				sendSizes.add(new int[] {sw, 18});
				bodyH += 20;
				maxSendW = Math.max(maxSendW, sw);
			}
			if (b.sends.isEmpty()) {
				bodyH += 16;
				maxSendW = font.width("drop a send block here") + 12;
			}
			int arm = 12;
			int w = Math.max(Math.max(headerW, arm + maxSendW + 10), 130);
			int footH = 10;
			// C-block
			block(g, x0, y, w, headerH, CTRL, CTRL_B);
			block(g, x0, y + headerH - 1, arm, bodyH + 2, CTRL, CTRL_B);
			block(g, x0, y + headerH + bodyH, Math.min(w, 90), footH, CTRL, CTRL_B);
			g.fill(x0 + 1, y + headerH - 1, x0 + arm - 1, y + headerH + bodyH + 1, CTRL);
			g.text(font, label, x0 + 8, y + (headerH - 8) / 2, TEXT, true);
			if (b.when != null) {
				int ex = x0 + 8 + labelW + 6;
				int ey = y + (headerH - expr[1]) / 2;
				Branch bb = b;
				drawExpr(g, b.when, new Holder() {
					@Override
					public Condition get() {
						return bb.when;
					}

					@Override
					public void set(Condition c) {
						bb.when = c;
					}
				}, ex, ey);
				g.text(font, "then", ex + expr[0] + 6, y + (headerH - 8) / 2, TEXT, true);
			}
			if (i == liveBranch) {
				g.text(font, "◀ now", x0 + w + 6, y + (headerH - 8) / 2, 0xFF55DD77, false);
			}
			hits.add(new Hit(HKind.BRANCH, x0, y, w, headerH, b, i, null, null));
			int sy = y + headerH + 2;
			int sx = x0 + arm + 2;
			for (int j = 0; j < b.sends.size(); j++) {
				Send s = b.sends.get(j);
				hits.add(new Hit(HKind.SEND_DROP, sx, sy - 3, Math.max(60, sendSizes.get(j)[0]), 5, b, j, null, null));
				int sw = sendSizes.get(j)[0];
				block(g, sx, sy, sw, 18, SEND, SEND_B);
				g.text(font, sendText(s), sx + 8, sy + 5, TEXT, true);
				hits.add(new Hit(HKind.SEND, sx, sy, sw, 18, b, j, s, null));
				sy += 20;
			}
			if (b.sends.isEmpty()) {
				outline(g, sx, sy, maxSendW, 14, 0x60FFFFFF);
				g.text(font, "drop a send block here", sx + 6, sy + 3, 0xA0FFFFFF, false);
				hits.add(new Hit(HKind.SEND_DROP, sx, sy, maxSendW, 14, b, 0, null, null));
			} else {
				hits.add(new Hit(HKind.SEND_DROP, sx, sy - 3, 80, 6, b, b.sends.size(), null, null));
			}
			maxW = Math.max(maxW, w + 50);
			y += headerH + bodyH + footH + 2;
		}
		hits.add(new Hit(HKind.BRANCH_DROP, x0, y - 4, 200, 10, null, branches.size(), null, null));
		if (branches.isEmpty()) {
			g.text(font, "Drag an 'if … then' or a 'send message' block here", x0 + 4, y + 6, 0xA0FFFFFF, false);
			hits.add(new Hit(HKind.EMPTY_CANVAS, x0, y, 260, 30, null, 0, null, null));
			y += 30;
		}
		contentH = y - startY + 20;
		contentW = maxW + 20;
	}

	private String labelFor(int i) {
		Branch b = rule.branches.get(i);
		if (b.when == null) {
			return rule.branches.size() == 1 ? "always" : "else";
		}
		return i == 0 ? "if" : "else if";
	}

	private String sendText(Send s) {
		String t = s.message == null ? "" : s.message.title == null || s.message.title.isBlank() ? s.message.body : s.message.title;
		t = t == null || t.isBlank() ? "(empty message)" : "“" + firstLine(t) + "”";
		if (t.length() > 34) {
			t = t.substring(0, 33) + "…”";
		}
		List<String> names = new ArrayList<>();
		for (String n : s.notifiers) {
			NotifierConfig nc = rt.config().notifier(n);
			names.add(nc == null ? n + "?" : nc.displayName());
		}
		String prio = s.message != null && s.message.priority != null && !s.message.priority.equals("default") ? " [" + s.message.priority + "]" : "";
		return "send " + t + " to " + (names.isEmpty() ? "nobody" : String.join(", ", names)) + prio;
	}

	private static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl) + " …";
	}

	private String triggerText() {
		Trigger t = rule.trigger;
		return switch (t.kind == null ? "" : t.kind) {
			case Trigger.EVENT -> {
				if (t.event == null) {
					yield "… (click to choose)";
				}
				EventSpec spec = rt.events.get(t.event);
				String extra = "";
				if (t.params != null && t.params.has("pattern") && !t.params.get("pattern").getAsString().isBlank()) {
					extra = " “" + t.params.get("pattern").getAsString() + "”";
				} else if (t.params != null && t.params.has("mode") && t.params.get("mode").getAsString().equals("mentions_me")) {
					extra = " mentioning me";
				}
				yield (spec == null ? t.event : spec.description().toLowerCase(Locale.ROOT)) + extra;
			}
			case Trigger.CONDITION -> (t.condition == null ? "?" : t.condition.describe())
					+ ("on_exit".equals(t.mode) ? " stops" : "while_true".equals(t.mode) ? " (repeating)" : "");
			case Trigger.CHANGE -> (t.signal == null ? "a value" : t.signal) + " changes";
			case Trigger.SCHEDULE -> t.everySeconds > 0 ? "every " + Value.formatNumber(t.everySeconds) + " s"
					: t.atRealTime != null ? "it is " + t.atRealTime : t.atWorldTime != null ? "world time is " + t.atWorldTime : "on a schedule";
			default -> "…";
		};
	}

	// ---- expressions ----

	private static boolean isSlot(Condition c) {
		return c == null || (!c.isGroup() && (c.left == null || c.left.isEmpty()));
	}

	private String compareText(Condition c) {
		Op op = Op.parse(c.op);
		String left = c.left.describe();
		String ops = op == null ? c.op : op.label();
		if (op == null || op.arity() == 0) {
			return left + " " + ops;
		}
		String right = c.right == null ? "?" : c.right.describe();
		if (op.arity() == 2) {
			right += " and " + (c.right2 == null ? "?" : c.right2.describe());
		}
		return left + " " + ops + " " + right;
	}

	private String groupWord(Condition c) {
		return switch (c.op == null ? "all" : c.op) {
			case "any" -> "or";
			case "none" -> "not";
			default -> "and";
		};
	}

	/** {width, height} of a condition block. */
	private int[] measure(Condition c) {
		if (isSlot(c)) {
			return new int[] {34, 14};
		}
		if (!c.isGroup()) {
			return new int[] {font.width(compareText(c)) + 16, 14};
		}
		String word = groupWord(c);
		List<Condition> kids = c.children == null ? List.of() : c.children;
		int w = 8;
		int h = 14;
		boolean not = word.equals("not");
		if (not) {
			w += font.width("not") + 6;
		}
		for (int i = 0; i < kids.size(); i++) {
			int[] k = measure(kids.get(i));
			w += k[0];
			h = Math.max(h, k[1] + 6);
			if (i < kids.size() - 1) {
				w += font.width(not ? "or" : word) + 8;
			}
		}
		if (kids.isEmpty()) {
			w += 34;
		}
		if (dragging && pressed != null && pressed.isCond()) {
			w += 18;
		}
		return new int[] {w + 6, Math.max(h, 18)};
	}

	private void drawExpr(GuiGraphicsExtractor g, Condition c, Holder holder, int x, int y) {
		int[] size = measure(c);
		if (isSlot(c)) {
			hexBlock(g, x, y, size[0], size[1], SLOT_FILL, SLOT_B);
			hits.add(new Hit(HKind.SLOT, x, y, size[0], size[1], null, -1, null, holder));
			return;
		}
		if (!c.isGroup()) {
			hexBlock(g, x, y, size[0], size[1], SENSE, SENSE_B);
			g.text(font, compareText(c), x + 8, y + 3, TEXT, true);
			hits.add(new Hit(HKind.COND, x, y, size[0], size[1], null, -1, null, holder));
			return;
		}
		hexBlock(g, x, y, size[0], size[1], LOGIC, LOGIC_B);
		hits.add(new Hit(HKind.COND, x, y, size[0], size[1], null, -1, null, holder));
		String word = groupWord(c);
		boolean not = word.equals("not");
		int cx = x + 7;
		int ty = y + (size[1] - 8) / 2;
		if (not) {
			g.text(font, "not", cx, ty, TEXT, true);
			cx += font.width("not") + 6;
		}
		if (c.children == null) {
			c.children = new ArrayList<>();
		}
		List<Condition> kids = c.children;
		for (int i = 0; i < kids.size(); i++) {
			Condition k = kids.get(i);
			int[] ks = measure(k);
			int idx = i;
			drawExpr(g, k, new Holder() {
				@Override
				public Condition get() {
					return c.children.get(idx);
				}

				@Override
				public void set(Condition n) {
					c.children.set(idx, n);
				}

				@Override
				public Condition group() {
					return c;
				}
			}, cx, y + (size[1] - ks[1]) / 2);
			cx += ks[0];
			if (i < kids.size() - 1) {
				String w = not ? "or" : word;
				cx += 4;
				g.text(font, w, cx, ty, TEXT, true);
				hits.add(new Hit(HKind.GROUP_LABEL, cx - 2, y, font.width(w) + 4, size[1], null, -1, null, holder));
				cx += font.width(w) + 4;
			}
		}
		if (kids.isEmpty()) {
			hexBlock(g, cx, y + (size[1] - 14) / 2, 34, 14, SLOT_FILL, SLOT_B);
			hits.add(new Hit(HKind.GROUP_ADD, cx, y + (size[1] - 14) / 2, 34, 14, null, -1, null, holder));
			cx += 34;
		}
		if (dragging && pressed != null && pressed.isCond()) {
			hexBlock(g, cx + 2, y + (size[1] - 12) / 2, 14, 12, SLOT_FILL, SLOT_B);
			g.text(font, "+", cx + 6, y + (size[1] - 8) / 2, TEXT, false);
			hits.add(new Hit(HKind.GROUP_ADD, cx + 2, y + (size[1] - 12) / 2, 14, 12, null, -1, null, holder));
		}
	}

	// ---- drawing primitives ----

	private static void block(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
		g.fill(x, y, x + w, y + h, border);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, darker(fill));
	}

	/** A block with pointed ends, Scratch's shape for true/false values. */
	private static void hexBlock(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
		for (int i = 0; i < h; i++) {
			int in = (int) Math.round(Math.abs(i - (h - 1) / 2.0) * 0.6);
			g.fill(x + in, y + i, x + w - in, y + i + 1, border);
			if (i > 0 && i < h - 1) {
				g.fill(x + in + 1, y + i, x + w - in - 1, y + i + 1, fill);
			}
		}
	}

	private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x - 1, y - 1, x + w + 1, y, color);
		g.fill(x - 1, y + h, x + w + 1, y + h + 1, color);
		g.fill(x - 1, y, x, y + h, color);
		g.fill(x + w, y, x + w + 1, y + h, color);
	}

	private static int darker(int c) {
		int a = c >>> 24;
		int r = (c >> 16 & 0xFF) * 3 / 4;
		int gg = (c >> 8 & 0xFF) * 3 / 4;
		int b = (c & 0xFF) * 3 / 4;
		return a << 24 | r << 16 | gg << 8 | b;
	}

	// ---- input ----

	private PalItem paletteAt(double mx, double my) {
		if (mx < 4 || mx > palW - 4 || my < top || my > bottom) {
			return null;
		}
		int y0 = top + 4 - (int) palScroll;
		for (int i = 0; i < palette.size(); i++) {
			int y = y0 + palPos.get(i)[0];
			if (my >= y && my < y + palPos.get(i)[1]) {
				return palette.get(i);
			}
		}
		return null;
	}

	private Hit hitAt(double mx, double my) {
		if (mx < canvasX || my < top || my > bottom) {
			return null;
		}
		Hit best = null;
		for (Hit h : hits) {
			if (h.kind() == HKind.BRANCH_DROP || h.kind() == HKind.SEND_DROP || h.kind() == HKind.EMPTY_CANVAS) {
				continue;
			}
			if (h.contains(mx, my, 0) && (best == null || h.w() * h.h() <= best.w() * best.h())) {
				best = h;
			}
		}
		return best;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		double mx = e.x();
		double my = e.y();
		pressButton = e.button();
		PalItem p = paletteAt(mx, my);
		if (p != null && !p.header() && e.button() == 0) {
			pressed = p.payload();
			pressedHit = null;
			pressX = mx;
			pressY = my;
			dragging = false;
			return true;
		}
		Hit h = hitAt(mx, my);
		if (h != null) {
			if (e.button() == 1) {
				deleteAt(h);
				return true;
			}
			pressedHit = h;
			pressed = null;
			pressX = mx;
			pressY = my;
			dragging = false;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (pressButton != 0) {
			return super.mouseDragged(e, dx, dy);
		}
		if (!dragging && (pressed != null || pressedHit != null)
				&& Math.abs(e.x() - pressX) + Math.abs(e.y() - pressY) > 4) {
			if (pressed == null && pressedHit != null) {
				pressed = pickUp(pressedHit);
			}
			dragging = pressed != null;
		}
		return dragging || super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		double mx = e.x();
		double my = e.y();
		if (dragging && pressed != null) {
			boolean overPalette = mx < palW + 2 && my > top && my < bottom;
			Hit target = overPalette ? null : findDrop(pressed, mx, my);
			if (target != null) {
				drop(pressed, target);
			} else if (!overPalette && restore != null) {
				restore.run();
			}
			restore = null;
			mutated();
		} else if (pressedHit != null && e.button() == 0) {
			click(pressedHit);
		}
		pressed = null;
		pressedHit = null;
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (mx < palW + 2 && my > top && my < bottom) {
			palScroll = Math.max(0, Math.min(Math.max(0, palContentH - (bottom - top) + 8), palScroll - sy * 20));
			return true;
		}
		if (mx >= canvasX && my > top && my < bottom) {
			if (sx != 0) {
				scrollX = Math.max(0, Math.min(Math.max(0, contentW - (width - canvasX)), scrollX - sx * 20));
			} else {
				scrollY = Math.max(0, Math.min(Math.max(0, contentH - (bottom - top)), scrollY - sy * 20));
			}
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.hasControlDown() && e.key() == 90 && !(getFocused() instanceof EditBox)) {
			doUndo();
			return true;
		}
		return super.keyPressed(e);
	}

	// ---- drag and drop ----

	/** Removes the picked block from the program and remembers how to put it back. */
	private Payload pickUp(Hit h) {
		switch (h.kind()) {
			case SEND -> {
				pushUndo();
				Branch b = h.branch();
				int idx = h.index();
				Send s = b.sends.remove(idx);
				restore = () -> b.sends.add(Math.min(idx, b.sends.size()), s);
				return new Payload(PKind.MOVE_SEND, s, "send", SEND);
			}
			case BRANCH -> {
				pushUndo();
				int idx = h.index();
				Branch b = rule.branches.remove(idx);
				restore = () -> rule.branches.add(Math.min(idx, rule.branches.size()), b);
				return new Payload(PKind.MOVE_BRANCH, b, b.when == null ? "else" : "if", CTRL);
			}
			case COND -> {
				pushUndo();
				Holder holder = h.holder();
				Condition c = holder.get();
				Condition group = holder.group();
				if (group != null && group.children.size() > 1) {
					int idx = group.children.indexOf(c);
					group.children.remove(idx);
					restore = () -> group.children.add(Math.min(idx, group.children.size()), c);
				} else {
					holder.set(slot());
					restore = () -> holder.set(c);
				}
				return new Payload(PKind.MOVE_COND, c, c.isGroup() ? groupWord(c) : compareText(c), c.isGroup() ? LOGIC : SENSE);
			}
			default -> {
				return null;
			}
		}
	}

	private Hit findDrop(Payload p, double mx, double my) {
		if (mx < canvasX || my < top || my > bottom) {
			return null;
		}
		if (p.isBranch()) {
			Hit best = null;
			double bestD = 60;
			for (Hit h : hits) {
				if (h.kind() == HKind.BRANCH_DROP) {
					double d = Math.abs(my - (h.y() + h.h() / 2.0));
					if (d < bestD) {
						bestD = d;
						best = h;
					}
				}
			}
			return best;
		}
		if (p.isSend()) {
			for (Hit h : hits) {
				if (h.kind() == HKind.SEND_DROP && h.contains(mx, my, 4)) {
					return h;
				}
			}
			for (Hit h : hits) {
				if (h.kind() == HKind.SEND && h.contains(mx, my, 0)) {
					return new Hit(HKind.SEND_DROP, h.x(), h.y() + h.h() - 2, h.w(), 4, h.branch(), h.index() + 1, null, null);
				}
			}
			for (Hit h : hits) {
				if (h.kind() == HKind.BRANCH && h.contains(mx, my, 0)) {
					return new Hit(HKind.SEND_DROP, h.x(), h.y() + h.h(), h.w(), 4, h.branch(), h.branch().sends.size(), null, null);
				}
			}
			for (Hit h : hits) {
				if (h.kind() == HKind.EMPTY_CANVAS && h.contains(mx, my, 20)) {
					return h;
				}
			}
			return null;
		}
		// conditions: the smallest slot/condition under the mouse, a group's + slot, or a branch header
		Hit best = null;
		for (Hit h : hits) {
			if ((h.kind() == HKind.SLOT || h.kind() == HKind.COND || h.kind() == HKind.GROUP_ADD) && h.contains(mx, my, 1)) {
				if (p.kind() == PKind.MOVE_COND && h.holder() != null && h.holder().get() == p.obj()) {
					continue;
				}
				if (best == null || h.w() * h.h() < best.w() * best.h()) {
					best = h;
				}
			}
		}
		if (best != null) {
			return best;
		}
		for (Hit h : hits) {
			if (h.kind() == HKind.BRANCH && h.contains(mx, my, 0)) {
				return h;
			}
		}
		return null;
	}

	private static Condition slot() {
		Condition c = new Condition();
		c.op = "eq";
		return c;
	}

	private Condition newCondition(Payload p) {
		return switch (p.kind()) {
			case AND -> Condition.group("all", slot(), slot());
			case OR -> Condition.group("any", slot(), slot());
			case NOT -> Condition.group("none", slot());
			case COMPARE -> compareFor((Operand) p.obj());
			case MOVE_COND -> (Condition) p.obj();
			default -> slot();
		};
	}

	/** A sensible starting comparison for a value: booleans "is true", numbers "< now", others "= now". */
	private Condition compareFor(Operand left) {
		Operand l = Json.copy(left, Operand.class);
		Map<String, ValueType> payloadTypes = triggerValues();
		Map<String, ValueType> varTypes = new LinkedHashMap<>();
		for (UserVariable v : rt.config().variables) {
			varTypes.put(v.name, ValueType.parse(v.type));
		}
		ValueType t = rt.engine.compiler().typeOf(l, payloadTypes, varTypes);
		Value now = Value.NONE;
		if (l.signal != null) {
			SignalRef ref = SignalRef.parse(l.signal);
			now = rt.engine.reader().read(ref.id(), ref.params());
		} else if (l.var != null) {
			now = rt.engine.variables().getOrDefault(l.var, Value.NONE);
		}
		return switch (t) {
			case BOOL -> Condition.compare(l, "is_true");
			case NUMBER -> Condition.compare(l, "lt", Operand.value(now.isNone() ? 0 : now.asNumber()));
			case LIST -> Condition.compare(l, "contains", Operand.value(""));
			case STRING -> Condition.compare(l, "contains", Operand.value(now.isNone() ? "" : now.asString()));
			default -> Condition.compare(l, "eq", Operand.value(now.isNone() ? "" : now.asString()));
		};
	}

	private void drop(Payload p, Hit target) {
		if (p.kind() != PKind.MOVE_BRANCH && p.kind() != PKind.MOVE_SEND && p.kind() != PKind.MOVE_COND) {
			pushUndo();
		}
		if (rule.branches == null) {
			rule.branches = new ArrayList<>();
		}
		if (p.isBranch()) {
			Branch b = p.kind() == PKind.MOVE_BRANCH ? (Branch) p.obj()
					: p.kind() == PKind.ELSE ? Branch.of(null) : Branch.of(slot());
			int idx = Math.min(target.index(), rule.branches.size());
			boolean hasElse = !rule.branches.isEmpty() && rule.branches.get(rule.branches.size() - 1).when == null;
			if (b.when == null) {
				if (hasElse) {
					ClientRuntime.warn("There is already an 'else' (or 'always') block.");
					if (restore != null) {
						restore.run();
					}
					return;
				}
				idx = rule.branches.size();
			} else if (hasElse && idx >= rule.branches.size()) {
				idx = rule.branches.size() - 1;
			}
			rule.branches.add(idx, b);
			if (p.kind() == PKind.IF && rule.branches.size() == 2 && rule.branches.get(1).when == null && rule.branches.get(1).sends.isEmpty()) {
				rule.branches.remove(1);
			}
			return;
		}
		if (p.isSend()) {
			Send s = p.kind() == PKind.MOVE_SEND ? (Send) p.obj() : newSend();
			if (target.kind() == HKind.EMPTY_CANVAS) {
				rule.branches.add(Branch.of(null, s));
				return;
			}
			Branch b = target.branch();
			b.sends.add(Math.min(target.index(), b.sends.size()), s);
			if (p.kind() == PKind.SEND) {
				minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.SEND, s));
			}
			return;
		}
		Condition c = newCondition(p);
		switch (target.kind()) {
			case SLOT -> target.holder().set(c);
			case GROUP_ADD -> {
				Condition group = target.holder().get();
				if (group.children == null) {
					group.children = new ArrayList<>();
				}
				group.children.add(c);
			}
			case COND -> {
				Condition existing = target.holder().get();
				if (p.kind() == PKind.AND || p.kind() == PKind.OR) {
					target.holder().set(Condition.group(p.kind() == PKind.AND ? "all" : "any", existing, slot()));
				} else if (p.kind() == PKind.NOT) {
					target.holder().set(Condition.group("none", existing));
				} else if (existing.isGroup()) {
					existing.children.add(c);
				} else if (target.holder().group() != null) {
					Condition g = target.holder().group();
					g.children.add(g.children.indexOf(existing) + 1, c);
				} else {
					target.holder().set(Condition.group("all", existing, c));
				}
			}
			case BRANCH -> {
				Branch b = target.branch();
				if (b.when == null) {
					b.when = c;
				} else {
					b.when = Condition.group("all", b.when, c);
				}
			}
			default -> {
			}
		}
		if (p.kind() == PKind.COMPARE && ((Operand) p.obj()).signal == null && ((Operand) p.obj()).var == null
				&& ((Operand) p.obj()).payload == null) {
			minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, c));
		}
	}

	private Send newSend() {
		Send s = new Send();
		s.message = MessageTemplate.of(rule.name == null || rule.name.isBlank() ? "" : rule.name, "");
		SculkwireConfig cfg = rt.config();
		if (cfg.notifiers.size() == 1) {
			s.notifiers.add(cfg.notifiers.get(0).id);
		}
		return s;
	}

	private void click(Hit h) {
		switch (h.kind()) {
			case HAT -> minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.TRIGGER, null));
			case SEND -> {
				pushUndo();
				minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.SEND, h.send()));
			}
			case COND -> {
				Condition c = h.holder().get();
				if (c.isGroup()) {
					pushUndo();
					c.op = "all".equals(c.op) ? "any" : "any".equals(c.op) ? "all" : c.op;
					mutated();
				} else {
					pushUndo();
					minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, c));
				}
			}
			case GROUP_LABEL -> {
				pushUndo();
				Condition c = h.holder().get();
				c.op = "all".equals(c.op) ? "any" : "any".equals(c.op) ? "all" : c.op;
				mutated();
			}
			case SLOT -> {
				Holder holder = h.holder();
				RuleEditScreen helper = RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, slot());
				helper.pickOperandFrom(this, op -> {
					pushUndo();
					Condition c = compareFor(op);
					holder.set(c);
					mutated();
					minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, c));
				});
			}
			default -> {
			}
		}
	}

	private void deleteAt(Hit h) {
		switch (h.kind()) {
			case SEND -> {
				pushUndo();
				h.branch().sends.remove(h.send());
			}
			case BRANCH -> {
				pushUndo();
				rule.branches.remove(h.branch());
			}
			case COND -> {
				pushUndo();
				Holder holder = h.holder();
				Condition group = holder.group();
				if (group != null && group.children.size() > 1) {
					group.children.remove(holder.get());
				} else {
					holder.set(slot());
				}
			}
			default -> {
				return;
			}
		}
		mutated();
	}

	// ---- actions ----

	private void test() {
		if (rt.engine.testFire(rule)) {
			ClientRuntime.info("Test of '" + rule.name + "' sent; see the History tab for the result.");
		} else {
			ClientRuntime.warn("Nothing to send: no block matches the current values, or no notifier is selected.");
		}
	}

	private void save() {
		if (rule.name == null || rule.name.isBlank()) {
			rule.name = isNew ? "New event" : originalId;
		}
		if (rule.hasBranches()) {
			rule.message = MessageTemplate.of("", "");
			rule.targets = new ArrayList<>();
		}
		SculkwireConfig cfg = rt.config();
		if (isNew || cfg.rule(originalId) == null) {
			rule.id = cfg.uniqueId(rule.name);
			cfg.rules.add(rule);
		} else {
			rule.id = originalId;
			for (int i = 0; i < cfg.rules.size(); i++) {
				if (cfg.rules.get(i).id.equals(originalId)) {
					cfg.rules.set(i, rule);
				}
			}
		}
		rt.apply(cfg);
		onClose();
	}

	@SuppressWarnings("unused")
	private static String typeName(ValueType t) {
		return t.id();
	}
}
