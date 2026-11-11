package dev.sculkwire.client.ui;

import dev.sculkwire.Sculkwire;
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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The event editor as Scratch-like blocks. A "when" hat (the trigger) is followed by a script of blocks
 * that run top to bottom: plain "send" blocks, and if / else if / else blocks that hold sends and other
 * blocks (nested to any depth). Conditions
 * are and/or/not blocks around comparisons. Drag blocks and live values from the palette; click a block
 * to edit it (click a notifier on a send block to switch it on or off); right-click or drag a block back
 * to the palette to delete it.
 */
public final class BlockEditorScreen extends Screen {
	private static final int HAT = 0xFFE6A800;
	private static final int HAT_B = 0xFFA87B00;
	private static final int CTRL = 0xFFFF9C1A;
	private static final int CTRL_B = 0xFFC2730F;
	private static final int SEND = 0xFF8F5CF0;
	private static final int SEND_B = 0xFF6640B8;
	private static final int CHIP_ON = 0xFFD9C7FF;
	private static final int CHIP_OFF = 0x50000000;
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

	/** Mouse buttons as 26.x numbers them (left = 1, right = 3; not GLFW's 0 and 1). */
	private static final int LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
	private static final int RIGHT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

	/** Set by the dev self-test so dropping a send does not open its popup. */
	static boolean selfTest;

	private enum PKind {
		IF, ELSE_IF, ELSE, SEND, AND, OR, NOT, COMPARE, MOVE_BRANCH, MOVE_SEND, MOVE_COND
	}

	private record Payload(PKind kind, Object obj, String label, int color) {
		boolean isBranch() {
			return kind == PKind.IF || kind == PKind.ELSE_IF || kind == PKind.ELSE || kind == PKind.MOVE_BRANCH;
		}

		boolean isSend() {
			return kind == PKind.SEND || kind == PKind.MOVE_SEND;
		}

		boolean isCond() {
			return kind == PKind.AND || kind == PKind.OR || kind == PKind.NOT || kind == PKind.COMPARE || kind == PKind.MOVE_COND;
		}

		boolean moving() {
			return kind == PKind.MOVE_BRANCH || kind == PKind.MOVE_SEND || kind == PKind.MOVE_COND;
		}
	}

	/** Where a condition lives, so it can be replaced. */
	private interface Holder {
		Condition get();

		void set(Condition c);

		default Condition group() {
			return null;
		}
	}

	private enum HKind {
		HAT, BRANCH, BRANCH_LABEL, SEND, CHIP, COND, SLOT, GROUP_LABEL, BRANCH_DROP, SEND_DROP, GROUP_ADD, EMPTY_CANVAS
	}

	/** A clickable part or drop zone; {@code list} is the block list a BRANCH_DROP zone inserts into. */
	private record Hit(HKind kind, int x, int y, int w, int h, Branch branch, int index, Send send, Holder holder, String id,
			List<Branch> list) {
		boolean contains(double px, double py, int pad) {
			return px >= x - pad && px < x + w + pad && py >= y - pad && py < y + h + pad;
		}

		boolean isZone() {
			return kind == HKind.BRANCH_DROP || kind == HKind.SEND_DROP || kind == HKind.EMPTY_CANVAS;
		}
	}

	private record PalItem(String label, boolean live, int color, Payload payload, String hint) {
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
	private List<Branch> running = List.of();
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
	private String filter = "";
	private String lastHint = "";
	private final List<Hit> hits = new ArrayList<>();
	private final List<PalItem> palette = new ArrayList<>();
	private final List<int[]> palPos = new ArrayList<>();

	private Payload pressed;
	private Hit pressedHit;
	private double pressX;
	private double pressY;
	private boolean dragging;
	private Runnable restore;
	private int pressButton = -1;

	public BlockEditorScreen(Screen parent, Rule rule, boolean isNew) {
		super(Component.literal(isNew ? "New event" : "Edit event"));
		this.parent = parent;
		this.rule = rule;
		this.isNew = isNew;
		this.originalId = rule.id;
		normalize();
		recompile();
	}

	/** An example script for screenshots. */
	public static Rule demoRule() {
		Rule r = new Rule();
		r.name = "Health check while away";
		r.trigger = Trigger.event("player.damaged");
		String n = ClientRuntime.get().config().notifiers.isEmpty() ? "phone" : ClientRuntime.get().config().notifiers.get(0).id;
		Condition notPlaying = Condition.group("none", Condition.compare(Operand.signal("session.actively_playing"), "is_true"));
		Condition low = Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6));
		r.branches = new ArrayList<>(List.of(
				Branch.typed(Branch.IF, Condition.group("all", low, notPlaying),
						Send.of(MessageTemplate.of("Low health while away!", "{player.health|hearts} hearts"), n)),
				Branch.typed(Branch.ELSE_IF, Condition.compare(Operand.signal("player.on_fire"), "is_true"),
						Send.of(MessageTemplate.of("You are burning", "{damage.source}"), n)),
				Branch.typed(Branch.ELSE, null, Send.of(MessageTemplate.of("Took {damage.amount} damage", ""), n)),
				Branch.typed(Branch.DO, null, Send.of(MessageTemplate.of("Damage log", "{damage.amount} from {damage.source}")))));
		return r;
	}

	/** The script shown in the Modrinth gallery: nested ifs, several notifiers, a send at the end. */
	public static Rule galleryRule() {
		Rule r = new Rule();
		r.name = "Danger while away";
		r.trigger = Trigger.event("player.damaged");
		Condition low = Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6));
		Condition away = Condition.group("none", Condition.compare(Operand.signal("session.actively_playing"), "is_true"));
		Branch lowAway = Branch.typed(Branch.IF, Condition.group("all", low, away),
				Send.of(MessageTemplate.of("Low health while away!", "{player.health|hearts} hearts at {player.block_pos}"), "phone", "discord"));
		lowAway.blocks = new ArrayList<>(List.of(
				Branch.typed(Branch.IF, Condition.compare(Operand.signal("nearby.hostiles", "radius", 8), "gte", Operand.value(3)),
						Send.of(MessageTemplate.of("Mobs around you", "{nearby.hostiles(radius=8)} hostiles within 8 blocks"), "phone"))));
		r.branches = new ArrayList<>(List.of(
				lowAway,
				Branch.typed(Branch.ELSE_IF, Condition.compare(Operand.signal("player.on_fire"), "is_true"),
						Send.of(MessageTemplate.of("You are burning", "{damage.source}"), "phone")),
				Branch.typed(Branch.ELSE, null, Send.of(MessageTemplate.of("Took {damage.amount} damage", ""), "toast")),
				Branch.typed(Branch.DO, null, Send.of(MessageTemplate.of("Damage log", "{damage.amount} from {damage.source}"), "log"))));
		return r;
	}

	/** Puts the rule in block form: legacy message + targets become one send block; block types are made explicit. */
	private void normalize() {
		if (rule.trigger == null) {
			rule.trigger = new Trigger();
		}
		if (!rule.hasBranches()) {
			Send s = new Send();
			s.message = rule.message == null ? MessageTemplate.of("", "") : Json.copy(rule.message, MessageTemplate.class);
			for (Target t : rule.targets) {
				if (!s.notifiers.contains(t.notifier)) {
					s.notifiers.add(t.notifier);
				}
			}
			rule.branches = new ArrayList<>();
			rule.branches.add(Branch.typed(Branch.DO, null, s));
		}
		fixTypes(rule.branches);
	}

	private static void fixTypes(List<Branch> blocks) {
		if (blocks == null) {
			return;
		}
		List<String> types = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			types.add(Branch.typeOf(blocks, i));
		}
		for (int i = 0; i < blocks.size(); i++) {
			blocks.get(i).type = types.get(i);
			fixTypes(blocks.get(i).blocks);
		}
	}

	private void cleanup() {
		prune(rule.branches);
	}

	/** Drops empty send-only blocks, at every depth. */
	private static void prune(List<Branch> blocks) {
		if (blocks == null) {
			return;
		}
		blocks.removeIf(b -> Branch.DO.equals(b.type) && b.sends.isEmpty() && b.children().isEmpty());
		for (Branch b : blocks) {
			prune(b.blocks);
		}
	}

	private boolean runs(Branch b) {
		for (Branch r : running) {
			if (r == b) {
				return true;
			}
		}
		return false;
	}

	/** The list that holds {@code b} (top level or inside another block). */
	private List<Branch> containerOf(Branch b) {
		List<Branch> found = findContainer(rule.branches, b);
		return found != null ? found : rule.branches;
	}

	private static List<Branch> findContainer(List<Branch> blocks, Branch b) {
		if (blocks == null) {
			return null;
		}
		for (Branch x : blocks) {
			if (x == b) {
				return blocks;
			}
		}
		for (Branch x : blocks) {
			List<Branch> inner = findContainer(x.blocks, b);
			if (inner != null) {
				return inner;
			}
		}
		return null;
	}

	/** True when {@code list} is the nested list of {@code b} or of a block inside it. */
	private static boolean inside(Branch b, List<Branch> list) {
		return b != null && list != null && b.blocks != null && (b.blocks == list || findContainerList(b.blocks, list));
	}

	private static boolean findContainerList(List<Branch> blocks, List<Branch> list) {
		for (Branch x : blocks) {
			if (x.blocks != null && (x.blocks == list || findContainerList(x.blocks, list))) {
				return true;
			}
		}
		return false;
	}

	private void recompile() {
		cleanup();
		compiled = rt.engine.compiler().compile(rule, rt.config());
		running = List.of();
		if (compiled.errors.isEmpty()) {
			running = rt.engine.previewRunning(compiled);
		}
	}

	private void pushUndo() {
		undo.push(Json.COMPACT.toJson(rule));
		while (undo.size() > 40) {
			undo.removeLast();
		}
	}

	private void doUndo() {
		if (!undo.isEmpty()) {
			rule = Json.COMPACT.fromJson(undo.pop(), Rule.class);
			recompile();
		}
	}

	// ---- screen setup ----

	@Override
	protected void init() {
		palW = Math.max(120, Math.min(170, width / 3));
		top = 44;
		bottom = height - 40;
		canvasX = palW + 6;
		recompile();
		rt.trackers.requestStatsSoon();
		addRenderableWidget(btn("⚙ Settings", "Name, limits and where this event applies",
				() -> minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.SETTINGS, null)), width - 236, 20, 80));
		addRenderableWidget(btn("Undo", "Ctrl+Z", this::doUndo, width - 152, 20, 46));
		addRenderableWidget(btn("Test send", "Send now with the current values (marked as test)", this::test, width - 102, 20, 96));
		EditBox search = new EditBox(font, 4, 20, palW - 4, 18, Component.empty());
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
			palette.add(new PalItem("Blocks", false, 0, null, null));
			palette.add(new PalItem("send message", false, SEND, new Payload(PKind.SEND, null, "send message", SEND),
					"Sends a message to the notifiers you click on it"));
			palette.add(new PalItem("if … then", false, CTRL, new Payload(PKind.IF, null, "if … then", CTRL),
					"Runs the sends inside when the condition is true"));
			palette.add(new PalItem("else if … then", false, CTRL, new Payload(PKind.ELSE_IF, null, "else if … then", CTRL),
					"After an if: runs when the if above did not, and this condition is true"));
			palette.add(new PalItem("else", false, CTRL, new Payload(PKind.ELSE, null, "else", CTRL),
					"After an if: runs when no if / else if above ran"));
			palette.add(new PalItem("… and …", false, LOGIC, new Payload(PKind.AND, null, "and", LOGIC), "Both must be true"));
			palette.add(new PalItem("… or …", false, LOGIC, new Payload(PKind.OR, null, "or", LOGIC), "One must be true"));
			palette.add(new PalItem("not …", false, LOGIC, new Payload(PKind.NOT, null, "not", LOGIC), "Must be false"));
			palette.add(new PalItem("Common", false, 0, null, null));
			for (String id : List.of("session.actively_playing", "session.afk_seconds", "player.health", "world.is_night",
					"nearby.hostiles", "player.dimension", "world.weather")) {
				addSignal(id);
			}
		}
		boolean first = true;
		for (Map.Entry<String, ValueType> e : triggerValues().entrySet()) {
			if (match(f, e.getKey(), "")) {
				if (first) {
					palette.add(new PalItem("This trigger", false, 0, null, null));
					first = false;
				}
				palette.add(new PalItem(e.getKey(), false, SENSE, new Payload(PKind.COMPARE, Operand.payload(e.getKey()), e.getKey(), SENSE),
						"A value of this trigger (" + e.getValue().id() + ")"));
			}
		}
		first = true;
		for (UserVariable v : rt.config().variables) {
			if (match(f, v.name, "")) {
				if (first) {
					palette.add(new PalItem("Your variables", false, 0, null, null));
					first = false;
				}
				palette.add(new PalItem(v.name, false, SENSE, new Payload(PKind.COMPARE, Operand.var(v.name), v.name, SENSE), "Your variable"));
			}
		}
		String cat = null;
		for (SignalSpec s : rt.signals.specs()) {
			if (!match(f, s.id(), s.description())) {
				continue;
			}
			if (!s.category().equals(cat)) {
				cat = s.category();
				palette.add(new PalItem(RuleEditScreen.cap(cat), false, 0, null, null));
			}
			addSignal(s.id());
		}
		palPos.clear();
		int y = 0;
		for (PalItem it : palette) {
			int h = it.header() ? 13 : 15;
			palPos.add(new int[] {y, h});
			y += h + (it.header() ? 1 : 2);
		}
		palContentH = y;
	}

	private void addSignal(String id) {
		SignalSpec s = rt.signals.spec(id);
		if (s != null) {
			palette.add(new PalItem(s.id(), true, SENSE, new Payload(PKind.COMPARE, Operand.signal(s.id()), s.id(), SENSE), s.description()));
		}
	}

	private static boolean match(String f, String a, String b) {
		return f.isEmpty() || a.toLowerCase(Locale.ROOT).contains(f) || b.toLowerCase(Locale.ROOT).contains(f);
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
		g.text(font, font.plainSubstrByWidth((isNew ? "New event: " : "Event: ") + name, width - canvasX - 240), canvasX, 26, TEXT, true);
		g.fill(0, top, palW + 2, bottom, PANEL);
		g.fill(canvasX - 2, top, width, bottom, 0x70000000);

		drawPalette(g, mx, my);

		g.enableScissor(canvasX, top, width, bottom);
		hits.clear();
		drawProgram(g);
		if (dragging && pressed != null) {
			Hit target = findDrop(pressed, mx, my);
			if (target != null) {
				if (target.kind() == HKind.BRANCH_DROP || target.kind() == HKind.SEND_DROP) {
					g.fill(target.x(), target.y() + target.h() / 2 - 1, target.x() + Math.max(80, target.w()), target.y() + target.h() / 2 + 1, DROP);
				} else {
					outline(g, target.x(), target.y(), target.w(), target.h(), DROP);
				}
			}
		}
		g.disableScissor();

		drawStatus(g);

		if (dragging && pressed != null) {
			int w = font.width(pressed.label()) + 14;
			int x = mx - 6;
			int y = my - 7;
			if (pressed.isCond()) {
				hexBlock(g, x, y, w, 14, pressed.color(), darker(pressed.color()));
			} else {
				block(g, x, y, w, 16, pressed.color(), darker(pressed.color()));
			}
			g.text(font, pressed.label(), x + 7, y + 4, TEXT, true);
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
			String val = "";
			if (it.live()) {
				Value v = rt.engine.reader().read(it.label(), Map.of());
				val = v.isNone() ? "–" : v.asString();
			}
			int valW = val.isEmpty() ? 0 : Math.min(font.width(val), w / 3);
			g.text(font, font.plainSubstrByWidth(it.label(), w - 12 - valW - (valW > 0 ? 4 : 0)), x + 6, y + 4, TEXT, true);
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
		PalItem hover = paletteAt(mx, my);
		if (hover != null && hover.hint() != null && !dragging) {
			lastHint = hover.hint();
		}
	}

	private void drawStatus(GuiGraphicsExtractor g) {
		String line;
		int color;
		if (!compiled.errors.isEmpty()) {
			line = "✖ " + compiled.errors.get(0);
			color = 0xFFFF6666;
		} else if (!compiled.warnings.isEmpty()) {
			line = "⚠ " + compiled.warnings.get(0);
			color = 0xFFFFC04D;
		} else {
			line = "✔ Ready" + (running.isEmpty() ? "" : " — blocks marked ◀ would run right now");
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

		String when = "when " + triggerText();
		String onlyIf = rule.conditions != null ? "only if " + rule.conditions.describe() : null;
		int hatW = Math.max(150, Math.max(font.width(when), onlyIf == null ? 0 : font.width(onlyIf)) + 18);
		int hatH = onlyIf == null ? 22 : 32;
		block(g, x0, y, hatW, hatH, HAT, HAT_B);
		g.text(font, when, x0 + 8, y + 7, TEXT, true);
		if (onlyIf != null) {
			g.text(font, onlyIf, x0 + 8, y + 19, 0xFFFFF2C0, false);
		}
		hits.add(hit(HKind.HAT, x0, y, hatW, hatH, null, -1, null, null, null));
		maxW = Math.max(maxW, hatW);
		y += hatH + 3;

		if (rule.branches == null) {
			rule.branches = new ArrayList<>();
		}
		List<Branch> blocks = rule.branches;
		progX = x0;
		int[] mw = {maxW};
		y = drawBlocks(g, blocks, x0, y, mw);
		maxW = mw[0];
		if (blocks.isEmpty()) {
			g.text(font, "Drag a 'send message' or 'if … then' block here", x0 + 4, y + 6, 0xA0FFFFFF, false);
			hits.add(zone(HKind.EMPTY_CANVAS, x0, y, 280, 34, blocks, 0));
			y += 34;
		}
		contentH = y - startY + 30;
		contentW = maxW + 30;
	}

	private static final int ARM = 12;
	/** Left edge of the script, for measuring how wide it is. */
	private int progX;
	private static final String EMPTY_BODY = "drop blocks here";

	/** Size of a control block as drawn. */
	private record Box(int w, int headerH, int bodyH, int labelW, int[] expr, int inner) {
	}

	private static String labelOf(Branch b) {
		return switch (b.type) {
			case Branch.IF -> "if";
			case Branch.ELSE_IF -> "else if";
			case Branch.ELSE -> "else";
			default -> b.type;
		};
	}

	private Box box(Branch b) {
		int labelW = font.width(labelOf(b));
		boolean conditional = b.conditional();
		if (conditional && b.when == null) {
			b.when = slot();
		}
		int[] expr = conditional ? measure(b.when) : new int[] {0, 0};
		int headerH = Math.max(20, expr[1] + 6);
		int headerW = conditional ? 8 + labelW + 6 + expr[0] + 6 + font.width("then") + 10 : labelW + 20;
		int bodyH = 4;
		int inner = 0;
		for (Send s : b.sends) {
			bodyH += 21;
			inner = Math.max(inner, sendWidth(s));
		}
		List<Branch> kids = b.children();
		if (!kids.isEmpty()) {
			int[] k = listSize(kids);
			bodyH += k[1];
			inner = Math.max(inner, k[0]);
		}
		if (b.sends.isEmpty() && kids.isEmpty()) {
			bodyH += 16;
			inner = font.width(EMPTY_BODY) + 12;
		}
		int w = Math.max(Math.max(headerW, ARM + inner + 10), 130);
		return new Box(w, headerH, bodyH, labelW, expr, inner);
	}

	/** {width, height} of a list of blocks as {@link #drawBlocks} draws it (width without the ◀ markers). */
	private int[] listSize(List<Branch> blocks) {
		int w = 0;
		int h = 0;
		for (Branch b : blocks) {
			if (Branch.DO.equals(b.type)) {
				for (Send s : b.sends) {
					w = Math.max(w, sendWidth(s));
				}
				h += b.sends.size() * 21 + 2;
			} else {
				Box bx = box(b);
				w = Math.max(w, bx.w());
				h += bx.headerH() + bx.bodyH() + 13;
			}
		}
		return new int[] {w, h};
	}

	/** Draws a list of blocks from (x0, y), nested blocks included; returns the y below it. */
	private int drawBlocks(GuiGraphicsExtractor g, List<Branch> blocks, int x0, int y, int[] maxW) {
		for (int i = 0; i < blocks.size(); i++) {
			Branch b = blocks.get(i);
			hits.add(zone(HKind.BRANCH_DROP, x0, y - 4, 220, 8, blocks, i));
			if (Branch.DO.equals(b.type)) {
				for (int j = 0; j < b.sends.size(); j++) {
					hits.add(hit(HKind.SEND_DROP, x0, y - 3, 160, 5, b, j, null, null, null));
					int w = drawSend(g, b, j, x0, y);
					if (runs(b)) {
						g.text(font, "◀", x0 + w + 4, y + 5, 0xFF55DD77, false);
					}
					maxW[0] = Math.max(maxW[0], x0 - progX + w + 20);
					y += 21;
				}
				hits.add(hit(HKind.SEND_DROP, x0, y - 3, 160, 5, b, b.sends.size(), null, null, null));
				y += 2;
				continue;
			}
			String label = labelOf(b);
			boolean conditional = b.conditional();
			Box bx = box(b);
			int headerH = bx.headerH();
			int bodyH = bx.bodyH();
			int w = bx.w();
			block(g, x0, y, w, headerH, CTRL, CTRL_B);
			block(g, x0, y + headerH - 1, ARM, bodyH + 2, CTRL, CTRL_B);
			block(g, x0, y + headerH + bodyH, Math.min(w, 90), 10, CTRL, CTRL_B);
			g.fill(x0 + 1, y + headerH - 1, x0 + ARM - 1, y + headerH + bodyH + 1, CTRL);
			g.text(font, label, x0 + 8, y + (headerH - 8) / 2, TEXT, true);
			hits.add(hit(HKind.BRANCH, x0, y, w, headerH, b, i, null, null, null));
			if (conditional) {
				hits.add(hit(HKind.BRANCH_LABEL, x0 + 4, y, bx.labelW() + 8, headerH, b, i, null, null, null));
				int ex = x0 + 8 + bx.labelW() + 6;
				drawExpr(g, b.when, new Holder() {
					@Override
					public Condition get() {
						return b.when;
					}

					@Override
					public void set(Condition c) {
						b.when = c;
					}
				}, ex, y + (headerH - bx.expr()[1]) / 2);
				g.text(font, "then", ex + bx.expr()[0] + 6, y + (headerH - 8) / 2, TEXT, true);
			}
			if (runs(b)) {
				g.text(font, "◀ now", x0 + w + 6, y + (headerH - 8) / 2, 0xFF55DD77, false);
			}
			int sy = y + headerH + 2;
			int sx = x0 + ARM + 2;
			for (int j = 0; j < b.sends.size(); j++) {
				hits.add(hit(HKind.SEND_DROP, sx, sy - 3, Math.max(80, sendWidth(b.sends.get(j))), 5, b, j, null, null, null));
				drawSend(g, b, j, sx, sy);
				sy += 21;
			}
			if (b.blocks == null) {
				b.blocks = new ArrayList<>();
			}
			if (b.sends.isEmpty() && b.blocks.isEmpty()) {
				outline(g, sx, sy, bx.inner(), 14, 0x60FFFFFF);
				g.text(font, EMPTY_BODY, sx + 6, sy + 3, 0xA0FFFFFF, false);
				hits.add(hit(HKind.SEND_DROP, sx, sy, bx.inner(), 14, b, 0, null, null, null));
				hits.add(zone(HKind.BRANCH_DROP, sx, sy, bx.inner(), 14, b.blocks, 0));
			} else {
				hits.add(hit(HKind.SEND_DROP, sx, sy - 3, 80, 6, b, b.sends.size(), null, null, null));
				if (b.blocks.isEmpty()) {
					hits.add(zone(HKind.BRANCH_DROP, sx, sy - 3, 160, 6, b.blocks, 0));
				} else {
					drawBlocks(g, b.blocks, sx, sy, maxW);
				}
			}
			maxW[0] = Math.max(maxW[0], x0 - progX + w + 50);
			y += headerH + bodyH + 10 + 3;
		}
		hits.add(zone(HKind.BRANCH_DROP, x0, y - 4, 220, 10, blocks, blocks.size()));
		return y;
	}

	private static Hit zone(HKind k, int x, int y, int w, int h, List<Branch> list, int index) {
		return new Hit(k, x, y, w, h, null, index, null, null, null, list);
	}

	private static Hit hit(HKind k, int x, int y, int w, int h, Branch b, int index, Send s, Holder holder, String id) {
		return new Hit(k, x, y, w, h, b, index, s, holder, id, null);
	}

	private String sendLabel(Send s) {
		String t = s.message == null ? "" : s.message.title == null || s.message.title.isBlank() ? s.message.body : s.message.title;
		t = t == null || t.isBlank() ? "(empty message)" : "“" + firstLine(t) + "”";
		if (t.length() > 30) {
			t = t.substring(0, 29) + "…”";
		}
		String prio = s.message != null && s.message.priority != null && !s.message.priority.equals("default") ? " [" + s.message.priority + "]" : "";
		return "send " + t + prio + " to";
	}

	private int sendWidth(Send s) {
		int w = 8 + font.width(sendLabel(s)) + 4;
		List<NotifierConfig> ns = rt.config().notifiers;
		if (ns.isEmpty()) {
			w += font.width("(add a notifier first)") + 4;
		}
		for (NotifierConfig n : ns) {
			w += font.width(n.displayName()) + 10;
		}
		return w + 4;
	}

	/** Draws a send block with a chip per notifier; returns its width. */
	private int drawSend(GuiGraphicsExtractor g, Branch b, int j, int x, int y) {
		Send s = b.sends.get(j);
		int w = sendWidth(s);
		block(g, x, y, w, 19, SEND, SEND_B);
		String label = sendLabel(s);
		g.text(font, label, x + 8, y + 6, TEXT, true);
		hits.add(hit(HKind.SEND, x, y, w, 19, b, j, s, null, null));
		int cx = x + 8 + font.width(label) + 4;
		List<NotifierConfig> ns = rt.config().notifiers;
		if (ns.isEmpty()) {
			g.text(font, "(add a notifier first)", cx, y + 6, 0xFFE0D0FF, false);
		}
		for (NotifierConfig n : ns) {
			String nm = n.displayName();
			int cw = font.width(nm) + 8;
			boolean on = s.notifiers.contains(n.id);
			g.fill(cx, y + 3, cx + cw, y + 16, on ? CHIP_ON : CHIP_OFF);
			g.text(font, nm, cx + 4, y + 6, on ? 0xFF3A2266 : 0xFFB8A8D8, false);
			hits.add(hit(HKind.CHIP, cx, y + 3, cw, 13, b, j, s, null, n.id));
			cx += cw + 2;
		}
		return w;
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

	// ---- condition expressions ----

	private static boolean isSlot(Condition c) {
		return c == null || (!c.isGroup() && (c.left == null || c.left.isEmpty()));
	}

	private String compareText(Condition c) {
		Op op = Op.parse(c.op);
		String left = c.left.describe();
		if ("player.stat".equals(c.left.signal) && c.left.params != null) {
			String cat = c.left.params.containsKey("category") ? c.left.params.get("category").getAsString() : "custom";
			String st = c.left.params.containsKey("stat") ? c.left.params.get("stat").getAsString() : "play_time";
			left = "stat " + (cat.equals("custom") ? "" : cat.replace('_', ' ') + " ") + st.replace("minecraft:", "").replace('_', ' ');
		}
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

	private static String groupWord(Condition c) {
		return switch (c.op == null ? "all" : c.op) {
			case "any" -> "or";
			case "none" -> "not";
			default -> "and";
		};
	}

	private boolean draggingCond() {
		return dragging && pressed != null && pressed.isCond();
	}

	private int[] measure(Condition c) {
		if (isSlot(c)) {
			return new int[] {34, 14};
		}
		if (!c.isGroup()) {
			return new int[] {font.width(compareText(c)) + 16, 14};
		}
		String word = groupWord(c);
		boolean not = word.equals("not");
		List<Condition> kids = c.children == null ? List.of() : c.children;
		int w = 8 + (not ? font.width("not") + 6 : 0);
		int h = 14;
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
		if (draggingCond()) {
			w += 18;
		}
		return new int[] {w + 6, Math.max(h, 18)};
	}

	private void drawExpr(GuiGraphicsExtractor g, Condition c, Holder holder, int x, int y) {
		int[] size = measure(c);
		if (isSlot(c)) {
			hexBlock(g, x, y, size[0], size[1], SLOT_FILL, SLOT_B);
			hits.add(hit(HKind.SLOT, x, y, size[0], size[1], null, -1, null, holder, null));
			return;
		}
		if (!c.isGroup()) {
			hexBlock(g, x, y, size[0], size[1], SENSE, SENSE_B);
			g.text(font, compareText(c), x + 8, y + 3, TEXT, true);
			hits.add(hit(HKind.COND, x, y, size[0], size[1], null, -1, null, holder, null));
			return;
		}
		hexBlock(g, x, y, size[0], size[1], LOGIC, LOGIC_B);
		hits.add(hit(HKind.COND, x, y, size[0], size[1], null, -1, null, holder, null));
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
		for (int i = 0; i < c.children.size(); i++) {
			Condition k = c.children.get(i);
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
			if (i < c.children.size() - 1) {
				String w = not ? "or" : word;
				cx += 4;
				g.text(font, w, cx, ty, TEXT, true);
				hits.add(hit(HKind.GROUP_LABEL, cx - 2, y, font.width(w) + 4, size[1], null, -1, null, holder, null));
				cx += font.width(w) + 4;
			}
		}
		if (c.children.isEmpty()) {
			hexBlock(g, cx, y + (size[1] - 14) / 2, 34, 14, SLOT_FILL, SLOT_B);
			hits.add(hit(HKind.GROUP_ADD, cx, y + (size[1] - 14) / 2, 34, 14, null, -1, null, holder, null));
			cx += 34;
		}
		if (draggingCond()) {
			hexBlock(g, cx + 2, y + (size[1] - 12) / 2, 14, 12, SLOT_FILL, SLOT_B);
			g.text(font, "+", cx + 6, y + (size[1] - 8) / 2, TEXT, false);
			hits.add(hit(HKind.GROUP_ADD, cx + 2, y + (size[1] - 12) / 2, 14, 12, null, -1, null, holder, null));
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
			if (!h.isZone() && h.contains(mx, my, 0) && (best == null || h.w() * h.h() <= best.w() * best.h())) {
				best = h;
			}
		}
		return best;
	}

	private static final boolean DEBUG_MOUSE = false;
	private int dragEvents;

	private void dbg(String fmt, Object... args) {
		if (DEBUG_MOUSE) {
			Sculkwire.LOG.info("[blocks] " + fmt, args);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mx = e.x();
		double my = e.y();
		pressButton = e.button();
		dragEvents = 0;
		PalItem p = paletteAt(mx, my);
		Hit dh = hitAt(mx, my);
		dbg("press x={} y={} button={} palette={} hit={} (palW={} top={} bottom={} canvasX={} hits={})", (int) mx, (int) my,
				e.button(), p == null ? null : p.label(), dh == null ? null : dh.kind(), palW, top, bottom, canvasX, hits.size());
		if (p != null && !p.header()) {
			if (e.button() == LEFT) {
				pressed = p.payload();
				pressedHit = null;
				pressX = mx;
				pressY = my;
				dragging = false;
			}
			return true;
		}
		Hit h = hitAt(mx, my);
		if (h != null) {
			if (e.button() == RIGHT) {
				deleteAt(h);
			} else if (e.button() == LEFT) {
				pressedHit = h;
				pressed = null;
				pressX = mx;
				pressY = my;
				dragging = false;
			}
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragEvents++ < 3 || dragEvents % 20 == 0) {
			dbg("drag #{} x={} y={} button={} pressButton={} pressed={} pressedHit={} dragging={}", dragEvents, (int) e.x(), (int) e.y(),
					e.button(), pressButton, pressed == null ? null : pressed.kind(), pressedHit == null ? null : pressedHit.kind(), dragging);
		}
		if (pressButton == LEFT && (pressed != null || pressedHit != null)) {
			if (!dragging && Math.abs(e.x() - pressX) + Math.abs(e.y() - pressY) > 3) {
				if (pressed == null) {
					pressed = pickUp(pressedHit);
				}
				dragging = pressed != null;
			}
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		boolean handled = pressed != null || pressedHit != null;
		double mx = e.x();
		double my = e.y();
		dbg("release x={} y={} button={} dragging={} pressed={} dragEvents={}", (int) mx, (int) my, e.button(), dragging,
				pressed == null ? null : pressed.kind(), dragEvents);
		if (dragging && pressed != null) {
			boolean overPalette = mx < palW + 2 && my > top && my < bottom;
			Hit target = overPalette ? null : findDrop(pressed, mx, my);
			dbg("drop target={}", target == null ? null : target.kind() + " index " + target.index());
			if (target != null) {
				drop(pressed, target);
			} else if (!overPalette && restore != null) {
				restore.run();
			}
			restore = null;
			recompile();
		} else if (pressedHit != null && e.button() == LEFT) {
			click(pressedHit, mx);
		}
		pressed = null;
		pressedHit = null;
		dragging = false;
		pressButton = -1;
		return handled || super.mouseReleased(e);
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

	private Payload pickUp(Hit h) {
		switch (h.kind()) {
			case SEND, CHIP -> {
				pushUndo();
				Branch b = h.branch();
				List<Branch> list = containerOf(b);
				int bi = list.indexOf(b);
				int idx = h.index();
				Send s = b.sends.remove(idx);
				boolean removedBlock = Branch.DO.equals(b.type) && b.sends.isEmpty();
				if (removedBlock) {
					list.remove(b);
				}
				restore = () -> {
					if (removedBlock) {
						list.add(Math.min(bi, list.size()), b);
					}
					b.sends.add(Math.min(idx, b.sends.size()), s);
				};
				return new Payload(PKind.MOVE_SEND, s, "send", SEND);
			}
			case BRANCH, BRANCH_LABEL -> {
				pushUndo();
				List<Branch> list = containerOf(h.branch());
				int idx = list.indexOf(h.branch());
				Branch b = list.remove(idx);
				restore = () -> list.add(Math.min(idx, list.size()), b);
				return new Payload(PKind.MOVE_BRANCH, b, b.type.replace('_', ' '), CTRL);
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

	/**
	 * The block drop zone nearest to the mouse. Zones further right than the mouse (inside a block) count as
	 * further away, so moving left puts a block after its parent instead of inside it.
	 */
	private Hit nearestBranchDrop(Payload p, double mx, double my, double max) {
		Branch moving = p.kind() == PKind.MOVE_BRANCH ? (Branch) p.obj() : null;
		Hit best = null;
		double bestD = max;
		for (Hit h : hits) {
			if (h.kind() == HKind.BRANCH_DROP && !inside(moving, h.list())) {
				double left = h.x() - 8;
				double d = Math.abs(my - (h.y() + h.h() / 2.0)) + (mx < left ? (left - mx) * 2 : 0);
				if (d < bestD || (d == bestD && best != null && h.x() > best.x())) {
					bestD = d;
					best = h;
				}
			}
		}
		return best;
	}

	private Hit findDrop(Payload p, double mx, double my) {
		if (mx < canvasX || my < top || my > bottom) {
			return null;
		}
		if (p.isBranch()) {
			return nearestBranchDrop(p, mx, my, 80);
		}
		if (p.isSend()) {
			for (Hit h : hits) {
				if (h.kind() == HKind.SEND_DROP && h.contains(mx, my, 4)) {
					return h;
				}
			}
			for (Hit h : hits) {
				if (h.kind() == HKind.SEND && h.contains(mx, my, 0)) {
					return hit(HKind.SEND_DROP, h.x(), h.y() + h.h() - 2, h.w(), 4, h.branch(), h.index() + 1, null, null, null);
				}
			}
			for (Hit h : hits) {
				if (h.kind() == HKind.BRANCH && h.contains(mx, my, 0)) {
					return hit(HKind.SEND_DROP, h.x(), h.y() + h.h(), h.w(), 4, h.branch(), h.branch().sends.size(), null, null, null);
				}
			}
			for (Hit h : hits) {
				if (h.kind() == HKind.EMPTY_CANVAS && h.contains(mx, my, 30)) {
					return h;
				}
			}
			return nearestBranchDrop(p, mx, my, 40);
		}
		Hit best = null;
		for (Hit h : hits) {
			if ((h.kind() == HKind.SLOT || h.kind() == HKind.COND || h.kind() == HKind.GROUP_ADD) && h.contains(mx, my, 2)) {
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

	/** A starting comparison for a value: booleans "is true", numbers "&lt; now", others "= now". */
	private Condition compareFor(Operand left) {
		Operand l = Json.copy(left, Operand.class);
		Map<String, ValueType> varTypes = new LinkedHashMap<>();
		for (UserVariable v : rt.config().variables) {
			varTypes.put(v.name, ValueType.parse(v.type));
		}
		ValueType t = rt.engine.compiler().typeOf(l, triggerValues(), varTypes);
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
		if (!p.moving()) {
			pushUndo();
		}
		if (rule.branches == null) {
			rule.branches = new ArrayList<>();
		}
		if (p.isBranch()) {
			Branch b = switch (p.kind()) {
				case MOVE_BRANCH -> (Branch) p.obj();
				case ELSE -> Branch.typed(Branch.ELSE, null);
				case ELSE_IF -> Branch.typed(Branch.ELSE_IF, slot());
				default -> Branch.typed(Branch.IF, slot());
			};
			List<Branch> list = target.list() != null ? target.list() : rule.branches;
			if (inside(b, list)) {
				if (restore != null) {
					restore.run();
				}
				return;
			}
			list.add(Math.min(Math.max(0, target.index()), list.size()), b);
			fixTypes(list);
			return;
		}
		if (p.isSend()) {
			Send s = p.kind() == PKind.MOVE_SEND ? (Send) p.obj() : newSend();
			if (target.kind() == HKind.EMPTY_CANVAS || target.kind() == HKind.BRANCH_DROP) {
				List<Branch> list = target.list() != null ? target.list() : rule.branches;
				int idx = target.kind() == HKind.EMPTY_CANVAS ? list.size() : target.index();
				list.add(Math.min(idx, list.size()), Branch.typed(Branch.DO, null, s));
			} else {
				Branch b = target.branch();
				b.sends.add(Math.min(target.index(), b.sends.size()), s);
			}
			if (p.kind() == PKind.SEND && !selfTest) {
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
				if (b.conditional()) {
					b.when = isSlot(b.when) ? c : Condition.group("all", b.when, c);
				} else if (Branch.ELSE.equals(b.type)) {
					b.type = Branch.ELSE_IF;
					b.when = c;
				}
			}
			default -> {
			}
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

	private void click(Hit h, double mx) {
		switch (h.kind()) {
			case HAT -> minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.TRIGGER, null));
			case CHIP -> {
				pushUndo();
				if (!h.send().notifiers.remove(h.id())) {
					h.send().notifiers.add(h.id());
				}
				recompile();
			}
			case SEND -> {
				pushUndo();
				minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.SEND, h.send()));
			}
			case BRANCH_LABEL -> {
				pushUndo();
				Branch b = h.branch();
				b.type = Branch.IF.equals(b.type) ? Branch.ELSE_IF : Branch.IF;
				recompile();
			}
			case COND -> {
				Condition c = h.holder().get();
				pushUndo();
				if (c.isGroup()) {
					c.op = "all".equals(c.op) ? "any" : "any".equals(c.op) ? "all" : c.op;
					recompile();
				} else {
					minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, c));
				}
			}
			case GROUP_LABEL -> {
				pushUndo();
				Condition c = h.holder().get();
				c.op = "all".equals(c.op) ? "any" : "any".equals(c.op) ? "all" : c.op;
				recompile();
			}
			case SLOT -> {
				Holder holder = h.holder();
				RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, slot()).pickOperandFrom(this, op -> {
					pushUndo();
					Condition c = compareFor(op);
					holder.set(c);
					recompile();
					minecraft.gui.setScreen(RuleEditScreen.popup(this, rule, RuleEditScreen.Section.COMPARE, c));
				});
			}
			default -> {
			}
		}
	}

	private void deleteAt(Hit h) {
		switch (h.kind()) {
			case SEND, CHIP -> {
				pushUndo();
				h.branch().sends.remove(h.send());
			}
			case BRANCH, BRANCH_LABEL -> {
				pushUndo();
				containerOf(h.branch()).remove(h.branch());
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
		recompile();
	}

	// ---- actions ----

	private void test() {
		if (rt.engine.testFire(rule)) {
			ClientRuntime.info("Test of '" + rule.name + "' sent; see the History tab for the result.");
		} else {
			ClientRuntime.warn("Nothing to send: no block runs with the current values, or no notifier is selected.");
		}
	}

	private void save() {
		if (rule.name == null || rule.name.isBlank()) {
			rule.name = isNew ? "New event" : originalId;
		}
		cleanup();
		Branch.forEach(rule.branches, b -> {
			if (b.blocks != null && b.blocks.isEmpty()) {
				b.blocks = null;
			}
		});
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

	// ---- dev self-test: simulated mouse input (see DevScreenshots, -Pdevshots=blocks) ----

	public static void registerSelfTest() {
		selfTest = true;
		BlockEditorScreen[] ed = new BlockEditorScreen[1];
		DevScreenshots.add("bt-0-open", () -> {
			Rule r = new Rule();
			r.name = "Drag test";
			r.trigger = Trigger.event("custom");
			r.branches = new ArrayList<>();
			ed[0] = new BlockEditorScreen(null, r, true);
			return ed[0];
		});
		DevScreenshots.addAction("bt-1-send-top", () -> ed[0].simulateDragPalette("send message", ed[0].canvasX + 60, ed[0].top + 50));
		DevScreenshots.addAction("bt-2-if", () -> ed[0].simulateDragPalette("if … then", ed[0].canvasX + 60, ed[0].top + 80));
		DevScreenshots.addAction("bt-3-value-into-slot", () -> {
			Hit slot = ed[0].first(HKind.SLOT);
			ed[0].simulateDragPalette("player.health", slot.x() + slot.w() / 2.0, slot.y() + slot.h() / 2.0);
		});
		DevScreenshots.addAction("bt-4-send-into-if", () -> {
			Hit zone = ed[0].firstInsideIf();
			ed[0].simulateDragPalette("send message", zone.x() + 20, zone.y() + zone.h() / 2.0);
		});
		DevScreenshots.addAction("bt-5-chip", () -> {
			Hit chip = ed[0].first(HKind.CHIP);
			if (chip != null) {
				ed[0].simulateClick(chip.x() + 3, chip.y() + 3);
			}
		});
		DevScreenshots.addAction("bt-6-and", () -> {
			Hit cond = ed[0].first(HKind.COND);
			ed[0].simulateDragPalette("… and …", cond.x() + 4, cond.y() + cond.h() / 2.0);
		});
		DevScreenshots.addAction("bt-7-else", () -> {
			Hit lastDrop = ed[0].last(HKind.BRANCH_DROP);
			ed[0].simulateDragPalette("else", lastDrop.x() + 30, lastDrop.y() + lastDrop.h() / 2.0);
		});
		DevScreenshots.addAction("bt-9-nested-if", () -> {
			Hit inner = ed[0].firstNestedZone();
			if (inner != null) {
				ed[0].simulateDragPalette("if … then", inner.x() + 20, inner.y() + inner.h() / 2.0);
			}
		});
		DevScreenshots.addAction("bt-8-move-send-down", () -> {
			Hit send = ed[0].first(HKind.SEND);
			Hit lastDrop = ed[0].last(HKind.BRANCH_DROP);
			ed[0].simulateDrag(send.x() + 5, send.y() + 5, lastDrop.x() + 30, lastDrop.y() + lastDrop.h() / 2.0);
		});
	}

	private Hit first(HKind k) {
		for (Hit h : hits) {
			if (h.kind() == k) {
				return h;
			}
		}
		return null;
	}

	private Hit last(HKind k) {
		Hit out = null;
		for (Hit h : hits) {
			if (h.kind() == k) {
				out = h;
			}
		}
		return out;
	}

	private Hit firstNestedZone() {
		for (Hit h : hits) {
			if (h.kind() == HKind.BRANCH_DROP && h.list() != null && h.list() != rule.branches) {
				return h;
			}
		}
		return null;
	}

	private Hit firstInsideIf() {
		for (Hit h : hits) {
			if (h.kind() == HKind.SEND_DROP && h.branch() != null && h.branch().conditional()) {
				return h;
			}
		}
		return null;
	}

	private void simulateDragPalette(String label, double toX, double toY) {
		for (int i = 0; i < palette.size(); i++) {
			if (palette.get(i).label().equals(label) && !palette.get(i).header()) {
				// Scroll the item into view first: in a small window it can be below the palette's bottom edge.
				int y0 = top + 4 - (int) palScroll;
				int itemY = y0 + palPos.get(i)[0] + 5;
				if (itemY > bottom - 4 || itemY < top + 4) {
					palScroll = Math.max(0, palPos.get(i)[0] - 20);
					y0 = top + 4 - (int) palScroll;
				}
				simulateDrag(10, y0 + palPos.get(i)[0] + 5, toX, toY);
				return;
			}
		}
		Sculkwire.LOG.warn("blocktest: no palette item '{}'", label);
	}

	private void simulateDrag(double x1, double y1, double x2, double y2) {
		MouseButtonInfo left = new MouseButtonInfo(LEFT, 0);
		mouseClicked(new MouseButtonEvent(x1, y1, left), false);
		for (int k = 1; k <= 6; k++) {
			double x = x1 + (x2 - x1) * k / 6;
			double y = y1 + (y2 - y1) * k / 6;
			mouseDragged(new MouseButtonEvent(x, y, left), (x2 - x1) / 6, (y2 - y1) / 6);
		}
		mouseReleased(new MouseButtonEvent(x2, y2, left));
		Sculkwire.LOG.info("blocktest: {}", Json.COMPACT.toJson(rule.branches));
	}

	private void simulateClick(double x, double y) {
		MouseButtonInfo left = new MouseButtonInfo(LEFT, 0);
		mouseClicked(new MouseButtonEvent(x, y, left), false);
		mouseReleased(new MouseButtonEvent(x, y, left));
		Sculkwire.LOG.info("blocktest: {}", Json.COMPACT.toJson(rule.branches));
	}

	@SuppressWarnings("unused")
	private static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
