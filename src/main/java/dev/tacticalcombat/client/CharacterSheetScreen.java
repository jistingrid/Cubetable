package dev.tacticalcombat.client;

import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.net.DiceRequestPayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.Expr;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The character sheet window: a draggable in-game window that draws whichever sheet format the selected character
 * uses, and turns every roll button into a dice request. Not connected to combat (yet).
 */
public final class CharacterSheetScreen extends Screen {
	private static final int WIN_W = 420;
	private static final int WIN_H = 272;
	private static final int TITLE_H = 16;
	private static final int BANNER_H = 54;
	private static final int TAB_H = 15;
	private static final int FOOT_H = 12;

	private static final int BG = 0xFF181B20;
	private static final int PANEL = 0xFF20242B;
	private static final int PANEL_HOVER = 0xFF2B313A;
	private static final int EDGE = 0xFF2C313A;
	private static final int GOLD = 0xFFE0B84C;
	private static final int MUTED = 0xFF9AA0A8;
	private static final int DIM = 0xFF6F7680;
	private static final int CRIMSON_A = 0xFF5C0F1B;
	private static final int CRIMSON_B = 0xFF8F1D2C;
	private static final int ROLL_BG = 0xFF2A2218;
	private static final int ROLL_EDGE = 0xFF7A6330;

	// the window remembers where it was, which character and page was open, and the advantage mode
	private static int winX = Integer.MIN_VALUE;
	private static int winY;
	private static int charIndex;
	private static int pageIndex;
	private static int rollMode; // 0 normal, 1 advantage, 2 disadvantage
	private enum View { SHEET, FORMATS, EDITOR }

	private static View view = View.SHEET;
	// character editor state (kept static so a window resize does not lose what was typed)
	private static SheetFormat editFormat;
	private static CharacterData editChar;
	private static final Map<String, String> formValues = new LinkedHashMap<>();
	private static String flash = "";
	private static long flashUntil;
	private static boolean loaded;
	private static boolean pendingOpen;

	private record Hit(int x, int y, int w, int h, Runnable action, String tip) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final float[] scroll = new float[8];
	private final float[] maxScroll = new float[8];
	private int mouseX;
	private int mouseY;
	private boolean dragging;
	private int formatSelected;

	private record FormItem(SheetFormat.Field field, TextFieldWidget widget, int relX, int relY, int labelW) {}

	private record FormHead(String text, int relY) {}

	private final List<FormItem> formItems = new ArrayList<>();
	private final List<FormHead> formHeads = new ArrayList<>();
	private int formHeight;
	private float formScroll;
	private float formMaxScroll;
	private boolean deleteArmed;

	public CharacterSheetScreen() {
		super(Text.literal("Character Sheet"));
	}

	/** Open from anywhere (a key, a command): it opens on the next client tick, once the chat has closed. */
	public static void requestOpen() {
		pendingOpen = true;
	}

	public static void tick(MinecraftClient client) {
		if (!pendingOpen || client.player == null) return;
		if (client.currentScreen == null || client.currentScreen instanceof TacticalScreen) {
			pendingOpen = false;
			if (!loaded) {
				SheetLibrary.reload();
				loaded = true;
			}
			client.setScreen(new CharacterSheetScreen());
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// the world stays visible behind the window
	}

	// ------------------------------------------------------------------ state helpers

	private CharacterData character() {
		if (SheetLibrary.CHARACTERS.isEmpty()) return null;
		charIndex = Math.floorMod(charIndex, SheetLibrary.CHARACTERS.size());
		return SheetLibrary.CHARACTERS.get(charIndex);
	}

	private SheetContext sheet() {
		CharacterData c = character();
		if (c == null) return null;
		SheetFormat f = SheetLibrary.FORMATS.get(c.format);
		return f == null ? null : new SheetContext(f, c);
	}

	private boolean hit(int x, int y, int w, int h, Runnable action, String tip) {
		hits.add(new Hit(x, y, w, h, action, tip));
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	// ------------------------------------------------------------------ render

	@Override
	public void render(DrawContext g, int mx, int my, float delta) {
		mouseX = mx;
		mouseY = my;
		hits.clear();

		int w = Math.min(WIN_W, width - 8);
		int h = Math.min(WIN_H, height - 8);
		if (winX == Integer.MIN_VALUE) {
			winX = (width - w) / 2;
			winY = (height - h) / 2;
		}
		winX = MathHelper.clamp(winX, 4 - w + 60, width - 60);
		winY = MathHelper.clamp(winY, 0, height - TITLE_H);
		int x = winX;
		int y = winY;

		g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF05060A);
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3B414C);
		g.fill(x, y, x + w, y + h, BG);

		String tooltip = null;
		drawTitleBar(g, x, y, w);
		if (view == View.FORMATS) {
			drawFormats(g, x, y + TITLE_H, w, h - TITLE_H);
		} else if (view == View.EDITOR) {
			drawEditor(g, x, y + TITLE_H, w, h - TITLE_H, delta);
		} else {
			SheetContext sc = sheet();
			if (sc == null) {
				drawEmpty(g, x, y + TITLE_H, w, h - TITLE_H);
			} else {
				drawBanner(g, sc, x, y + TITLE_H, w);
				drawTabs(g, sc, x, y + TITLE_H + BANNER_H, w);
				drawBody(g, sc, x, y + TITLE_H + BANNER_H + TAB_H, w, h - TITLE_H - BANNER_H - TAB_H - FOOT_H);
				drawFooter(g, x, y + h - FOOT_H, w);
			}
		}

		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tip != null && hit.contains(mx, my)) {
				tooltip = hit.tip;
				break;
			}
		}
		if (tooltip != null) {
			g.drawTooltip(textRenderer, Text.literal(tooltip), mx, my);
		}
	}

	private void drawTitleBar(DrawContext g, int x, int y, int w) {
		g.fill(x, y, x + w, y + TITLE_H, 0xFF0F1114);
		g.fill(x, y + TITLE_H - 1, x + w, y + TITLE_H, EDGE);

		int right = x + w - 3;
		right -= smallButton(g, right - 12, y + 3, "x", this::close, "Close", 0xFF7A1C27, 0xFFB8323F) + 3;
		if (view != View.EDITOR) {
			right -= smallButton(g, right - tw("Reload") - 6, y + 3, "Reload", () -> SheetLibrary.reload(), "Read the sheets and characters folders again", PANEL, 0xFF3B414C) + 3;
			right -= smallButton(g, right - tw("Formats") - 6, y + 3, view == View.FORMATS ? "Back" : "Formats",
					() -> view = view == View.FORMATS ? View.SHEET : View.FORMATS, "Installed sheet formats", PANEL, 0xFF3B414C) + 3;
			if (view == View.SHEET && sheet() != null) {
				right -= smallButton(g, right - tw("Edit") - 6, y + 3, "Edit", this::startEdit, "Change this character's details", PANEL, 0xFF3B414C) + 3;
			}
			right -= smallButton(g, right - tw("New") - 6, y + 3, "New", this::startNew, "Create a new character", PANEL, 0xFF3B414C) + 3;
		}

		// character switcher on the left
		CharacterData c = character();
		String name = c == null ? "No characters" : textRenderer.trimToWidth(c.displayName(), 90);
		int cx = x + 6;
		if (view == View.SHEET && SheetLibrary.CHARACTERS.size() > 1) {
			cx += smallButton(g, cx, y + 3, "<", () -> { charIndex--; resetScroll(); }, "Previous character", PANEL, 0xFF3B414C) + 3;
			g.drawText(textRenderer, name, cx, y + 4, 0xFFC9CCD2, false);
			cx += tw(name) + 4;
			smallButton(g, cx, y + 3, ">", () -> { charIndex++; resetScroll(); }, "Next character", PANEL, 0xFF3B414C);
		} else {
			String title = view == View.FORMATS ? "Sheet formats"
					: view == View.EDITOR ? (editChar == null ? "New character" : "Edit " + editChar.displayName()) : name;
			g.drawText(textRenderer, textRenderer.trimToWidth(title, Math.max(40, right - cx - 4)), cx, y + 4, 0xFFC9CCD2, false);
		}
	}

	private int smallButton(DrawContext g, int x, int y, String text, Runnable action, String tip, int fill, int edge) {
		int bw = Math.max(12, tw(text) + 6);
		boolean over = hit(x, y, bw, 11, action, tip);
		g.fill(x, y, x + bw, y + 11, edge);
		g.fill(x + 1, y + 1, x + bw - 1, y + 10, over ? PANEL_HOVER : fill);
		g.drawText(textRenderer, text, x + (bw - tw(text)) / 2, y + 2, 0xFFFFFFFF, false);
		return bw;
	}

	private void drawEmpty(DrawContext g, int x, int y, int w, int h) {
		CharacterData c = character();
		String msg = c == null
				? "No characters found. Put a .json file in config/tacticalcombat/characters/"
				: "Character \"" + c.displayName() + "\" uses the format \"" + c.format + "\", which is not installed.";
		List<OrderedText> lines = textRenderer.wrapLines(StringVisitable.plain(msg), w - 24);
		int ly = y + 24;
		for (OrderedText line : lines) {
			g.drawText(textRenderer, line, x + 12, ly, MUTED, false);
			ly += 10;
		}
		int ex = x + 12;
		ex += smallButton(g, ex, ly + 6, "New character", this::startNew, "Create a new character", PANEL, 0xFF3B414C) + 4;
		smallButton(g, ex, ly + 6, "Formats", () -> view = View.FORMATS, "Installed sheet formats", PANEL, 0xFF3B414C);
	}

	private void drawBanner(DrawContext g, SheetContext sc, int x, int y, int w) {
		// crimson gradient banner
		g.fillGradient(x, y, x + w, y + BANNER_H, CRIMSON_B, CRIMSON_A);
		g.fill(x, y + BANNER_H - 1, x + w, y + BANNER_H, 0xFF05060A);

		// portrait: the local player's skin
		g.fill(x + 7, y + 7, x + 47, y + 47, 0xFF05060A);
		g.fill(x + 8, y + 8, x + 46, y + 46, GOLD);
		g.fill(x + 9, y + 9, x + 45, y + 45, 0xFF2A1014);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			PlayerSkinDrawer.draw(g, mc.player.getSkinTextures(), x + 11, y + 11, 32);
		}

		String title = sc.template(sc.format.titleTemplate);
		String subtitle = sc.template(sc.format.subtitleTemplate);
		var ms = g.getMatrices();
		ms.push();
		ms.translate(x + 54, y + 7, 0);
		ms.scale(1.5f, 1.5f, 1.0f);
		g.drawText(textRenderer, title, 0, 0, 0xFFFFFFFF, true);
		ms.pop();
		g.drawText(textRenderer, subtitle, x + 54, y + 22, 0xFFF0CFD2, false);

		int bx = x + 54;
		for (SheetFormat.Bar bar : sc.format.bars) {
			double cur = sc.number(bar.value());
			double max = sc.number(bar.max());
			float frac = max > 0 && !Double.isNaN(cur) ? (float) MathHelper.clamp(cur / max, 0, 1) : 0;
			int bw = 88;
			g.fill(bx, y + 34, bx + bw, y + 45, 0xFF05060A);
			g.fill(bx + 1, y + 35, bx + bw - 1, y + 44, 0xFF1A0B0E);
			g.fill(bx + 1, y + 35, bx + 1 + Math.round((bw - 2) * frac), y + 44, bar.color());
			String label = bar.label() + " " + sc.show(bar.value(), false) + " / " + sc.show(bar.max(), false);
			g.drawCenteredTextWithShadow(textRenderer, label, bx + bw / 2, y + 35, 0xFFFFFFFF);
			bx += bw + 6;
		}

		int badgeX = x + w - 6;
		for (int i = sc.format.badges.size() - 1; i >= 0; i--) {
			SheetFormat.Badge b = sc.format.badges.get(i);
			badgeX -= 38;
			g.fill(badgeX, y + 9, badgeX + 36, y + 45, 0xFF05060A);
			g.fill(badgeX + 1, y + 10, badgeX + 35, y + 44, GOLD);
			g.fill(badgeX + 2, y + 11, badgeX + 34, y + 43, 0xFF1A0B0E);
			String v = sc.show(b.value(), b.signed());
			ms.push();
			ms.translate(badgeX + 18, y + 17, 0);
			ms.scale(1.5f, 1.5f, 1.0f);
			g.drawCenteredTextWithShadow(textRenderer, v, 0, 0, 0xFFFFFFFF);
			ms.pop();
			g.drawCenteredTextWithShadow(textRenderer, b.label(), badgeX + 18, y + 33, 0xFFF0CFD2);
			badgeX -= 2;
		}
	}

	private void drawTabs(DrawContext g, SheetContext sc, int x, int y, int w) {
		g.fill(x, y, x + w, y + TAB_H, 0xFF12151A);
		g.fill(x, y + TAB_H - 1, x + w, y + TAB_H, EDGE);
		pageIndex = MathHelper.clamp(pageIndex, 0, sc.format.pages.size() - 1);

		int tx = x + 6;
		for (int i = 0; i < sc.format.pages.size(); i++) {
			final int idx = i;
			String t = sc.format.pages.get(i).title;
			int bw = tw(t) + 14;
			boolean over = hit(tx, y, bw, TAB_H, () -> { pageIndex = idx; resetScroll(); }, null);
			boolean on = i == pageIndex;
			g.drawText(textRenderer, t, tx + 7, y + 4, on ? 0xFFFFFFFF : over ? 0xFFC9CCD2 : MUTED, false);
			if (on) g.fill(tx + 2, y + TAB_H - 3, tx + bw - 2, y + TAB_H - 1, GOLD);
			tx += bw;
		}

		// advantage mode for d20 rolls
		String[] names = {"Normal", "Advantage", "Disadvantage"};
		int rx = x + w - 6;
		for (int i = 2; i >= 0; i--) {
			final int mode = i;
			int bw = tw(names[i]) + 8;
			rx -= bw;
			boolean over = hit(rx, y + 2, bw, 11, () -> rollMode = mode, "Applies to single d20 rolls");
			boolean on = rollMode == i;
			g.fill(rx, y + 2, rx + bw, y + 13, on ? GOLD : 0xFF3B414C);
			g.fill(rx + 1, y + 3, rx + bw - 1, y + 12, on ? GOLD : over ? PANEL_HOVER : PANEL);
			g.drawText(textRenderer, names[i], rx + 4, y + 3, on ? 0xFF14161A : 0xFFC9CCD2, false);
			rx -= 2;
		}
	}

	private void drawBody(DrawContext g, SheetContext sc, int x, int y, int w, int h) {
		SheetFormat.Page page = sc.format.pages.get(pageIndex);
		float totalWeight = 0;
		for (SheetFormat.Column c : page.columns) totalWeight += c.weight;
		int gap = 4;
		int usable = w - 8 - gap * (page.columns.size() - 1);
		int cx = x + 4;
		for (int ci = 0; ci < page.columns.size() && ci < scroll.length; ci++) {
			SheetFormat.Column col = page.columns.get(ci);
			int cw = Math.round(usable * col.weight / totalWeight);
			maxScroll[ci] = drawColumn(g, sc, col, ci, cx, y + 3, cw, h - 3);
			cx += cw + gap;
		}
	}

	/** Draws one scrollable column; returns how far it can scroll. */
	private float drawColumn(DrawContext g, SheetContext sc, SheetFormat.Column col, int ci, int x, int y, int w, int h) {
		scroll[ci] = MathHelper.clamp(scroll[ci], 0, maxScroll[ci]);
		g.enableScissor(x, y, x + w, y + h);
		int cy = y - Math.round(scroll[ci]);
		int start = cy;
		for (SheetFormat.Section sec : col.sections) {
			if (!sec.title.isEmpty()) {
				g.drawText(textRenderer, sec.title, x + 1, cy + 1, GOLD, false);
				cy += 11;
			}
			for (SheetFormat.Item it : sec.items) {
				cy += drawItem(g, sc, it, x, cy, w, y, y + h) + 1;
			}
			cy += 4;
		}
		g.disableScissor();
		float content = cy - start;
		if (content > h) {
			float frac = h / content;
			int barH = Math.max(8, Math.round(h * frac));
			int barY = y + Math.round((h - barH) * (scroll[ci] / (content - h)));
			g.fill(x + w - 2, barY, x + w, barY + barH, 0xFF5A616C);
		}
		return Math.max(0, content - h);
	}

	/** One row of a section. Returns its height. */
	private int drawItem(DrawContext g, SheetContext sc, SheetFormat.Item it, int x, int y, int w, int clipTop, int clipBottom) {
		boolean hasText = !it.text.isEmpty();
		List<OrderedText> lines = hasText
				? textRenderer.wrapLines(StringVisitable.plain(it.text), (int) ((w - 8) / 0.75f)) : List.of();
		boolean hasSub = !it.sub.isEmpty();
		int h = hasText ? 12 + lines.size() * 7 + 3 : hasSub ? 19 : 13;
		if (y + h <= clipTop || y >= clipBottom) return h;

		// buttons, laid out from the right edge
		int count = it.buttons.size();
		int[] bxs = new int[count];
		int[] bws = new int[count];
		String[] texts = new String[count];
		int bx = x + w - 3;
		for (int i = count - 1; i >= 0; i--) {
			SheetFormat.Button b = it.buttons.get(i);
			String t = b.before + (b.value != null ? sc.show(b.value, b.signed) : "")
					+ (b.label.isEmpty() ? "" : (b.value != null || !b.before.isEmpty() ? " " : "") + b.label);
			texts[i] = t;
			bws[i] = tw(t) + 6;
			bx -= bws[i];
			bxs[i] = bx;
			bx -= 2;
		}
		int free = bx;

		boolean rowHover = false;
		if (it.roll != null) {
			final String roll = it.roll;
			final String label = it.rollLabel;
			rowHover = hit(x, y, w, h, () -> rollDice(sc, label, roll), "Roll " + roll);
		}
		g.fill(x, y, x + w, y + h, rowHover ? PANEL_HOVER : PANEL);
		g.fill(x, y, x + 1, y + h, EDGE);

		int tx = x + 4;
		if (it.mark != null) {
			boolean on = sc.number(it.mark) > 0;
			g.fill(x + 3, y + 4, x + 8, y + 9, on ? GOLD : 0xFF5A616C);
			if (!on) g.fill(x + 4, y + 5, x + 7, y + 8, PANEL);
			tx = x + 11;
		}

		String label = textRenderer.trimToWidth(it.label, Math.max(10, free - tx - (it.value != null ? 28 : 4)));
		g.drawText(textRenderer, label, tx, y + 3, 0xFFE8E6E1, false);

		if (it.value != null) {
			String v = sc.show(it.value, it.signed);
			String err = "?".equals(v) ? sc.error(it.value) : null;
			int vx = free - 3 - tw(v);
			boolean bad = err != null;
			g.drawText(textRenderer, v, vx, y + 3, bad ? 0xFFFF6B6B : 0xFFFFFFFF, true);
			if (bad) hit(vx, y, tw(v) + 3, 13, () -> {}, err);
		}

		if (hasSub) {
			var ms = g.getMatrices();
			ms.push();
			ms.translate(tx, y + 12, 0);
			ms.scale(0.75f, 0.75f, 1.0f);
			g.drawText(textRenderer, it.sub, 0, 0, MUTED, false);
			ms.pop();
		}
		if (hasText) {
			var ms = g.getMatrices();
			ms.push();
			ms.translate(x + 4, y + 12, 0);
			ms.scale(0.75f, 0.75f, 1.0f);
			int ly = 0;
			for (OrderedText line : lines) {
				g.drawText(textRenderer, line, 0, ly, MUTED, false);
				ly += 9;
			}
			ms.pop();
		}

		for (int i = 0; i < count; i++) {
			SheetFormat.Button b = it.buttons.get(i);
			boolean over = b.roll != null && hit(bxs[i], y + 1, bws[i], 11, () -> rollDice(sc, b.rollLabel, b.roll), "Roll " + b.roll);
			g.fill(bxs[i], y + 1, bxs[i] + bws[i], y + 12, ROLL_EDGE);
			g.fill(bxs[i] + 1, y + 2, bxs[i] + bws[i] - 1, y + 11, over ? 0xFF3A2A10 : ROLL_BG);
			g.drawText(textRenderer, texts[i], bxs[i] + 3, y + 3, 0xFFFFFFFF, false);
		}
		return h;
	}

	private void drawFooter(DrawContext g, int x, int y, int w) {
		g.fill(x, y, x + w, y + FOOT_H, 0xFF0F1114);
		g.fill(x, y, x + w, y + 1, EDGE);
		boolean flashing = System.currentTimeMillis() < flashUntil;
		g.drawText(textRenderer, flashing ? flash : "Click a value or button to roll. Drag the title bar to move.", x + 6, y + 2,
				flashing ? 0xFF7CE08A : DIM, false);
		if (!flashing && !SheetLibrary.PROBLEMS.isEmpty()) {
			String p = SheetLibrary.PROBLEMS.size() + " problem(s): see Formats";
			g.drawText(textRenderer, p, x + w - 6 - tw(p), y + 2, 0xFFFF6B6B, false);
		}
	}

	// ------------------------------------------------------------------ formats view

	private void drawFormats(DrawContext g, int x, int y, int w, int h) {
		List<SheetFormat> formats = new ArrayList<>(SheetLibrary.FORMATS.values());
		formatSelected = MathHelper.clamp(formatSelected, 0, Math.max(0, formats.size() - 1));
		CharacterData current = character();

		g.drawText(textRenderer, "INSTALLED", x + 8, y + 7, GOLD, false);
		int ly = y + 19;
		for (int i = 0; i < formats.size(); i++) {
			final int idx = i;
			SheetFormat f = formats.get(i);
			boolean on = i == formatSelected;
			boolean over = hit(x + 6, ly, 150, 22, () -> formatSelected = idx, null);
			g.fill(x + 6, ly, x + 156, ly + 22, on ? GOLD : EDGE);
			g.fill(x + 7, ly + 1, x + 155, ly + 21, over ? PANEL_HOVER : PANEL);
			g.drawText(textRenderer, textRenderer.trimToWidth(f.name, 140), x + 11, ly + 3, 0xFFE8E6E1, false);
			var ms = g.getMatrices();
			ms.push();
			ms.translate(x + 11, ly + 13, 0);
			ms.scale(0.75f, 0.75f, 1.0f);
			g.drawText(textRenderer, f.source + (current != null && current.format.equals(f.id) ? "  (in use)" : ""), 0, 0, MUTED, false);
			ms.pop();
			ly += 24;
		}

		int px = x + 166;
		int pw = w - 172;
		if (!formats.isEmpty()) {
			SheetFormat f = formats.get(formatSelected);
			g.drawText(textRenderer, "DEFINITION", px, y + 7, GOLD, false);
			int dy = y + 19;
			g.drawText(textRenderer, f.name + "  (id: " + f.id + ")", px, dy, 0xFFFFFFFF, false);
			dy += 11;
			for (OrderedText line : textRenderer.wrapLines(StringVisitable.plain(f.description), pw)) {
				g.drawText(textRenderer, line, px, dy, MUTED, false);
				dy += 9;
			}
			dy += 4;
			g.drawText(textRenderer, f.pages.size() + " page(s), " + f.derived.size() + " calculated value(s)", px, dy, MUTED, false);
			dy += 14;
		}

		int by = y + h - 56;
		g.drawText(textRenderer, "ADD A GAME", px, by, GOLD, false);
		String help = "Put a .json format file in the sheets folder, a character .json in the characters folder, then press Reload.";
		int hy = by + 11;
		for (OrderedText line : textRenderer.wrapLines(StringVisitable.plain(help), pw)) {
			g.drawText(textRenderer, line, px, hy, MUTED, false);
			hy += 9;
		}
		int bx = px;
		bx += smallButton(g, bx, hy + 3, "Open sheets folder", () -> Util.getOperatingSystem().open(SheetLibrary.sheetsDir().toFile()),
				SheetLibrary.sheetsDir().toString(), PANEL, 0xFF3B414C) + 4;
		smallButton(g, bx, hy + 3, "Open characters folder", () -> Util.getOperatingSystem().open(SheetLibrary.charactersDir().toFile()),
				SheetLibrary.charactersDir().toString(), PANEL, 0xFF3B414C);

		int py = ly + 4;
		for (String problem : SheetLibrary.PROBLEMS) {
			if (py > y + h - 12) break;
			g.drawText(textRenderer, textRenderer.trimToWidth(problem, 150), x + 8, py, 0xFFFF6B6B, false);
			py += 9;
		}
	}

	// ------------------------------------------------------------------ editor

	private void startNew() {
		view = View.EDITOR;
		editFormat = null; // step 1: choose the format
		editChar = null;
		deleteArmed = false;
		formValues.clear();
		rebuildForm();
	}

	private void startEdit() {
		SheetContext sc = sheet();
		if (sc == null) return;
		view = View.EDITOR;
		editFormat = sc.format;
		editChar = sc.character;
		deleteArmed = false;
		formValues.clear();
		for (SheetFormat.Field f : editFormat.editableFields()) {
			if (f.isText()) {
				formValues.put(f.id(), editChar.texts.getOrDefault(f.id(), f.def()));
			} else {
				Double v = editChar.values.get(f.id());
				formValues.put(f.id(), v == null ? f.def() : numberText(v));
			}
		}
		rebuildForm();
	}

	private void chooseFormat(SheetFormat format) {
		editFormat = format;
		formValues.clear();
		for (SheetFormat.Field f : format.editableFields()) {
			formValues.put(f.id(), f.def());
		}
		rebuildForm();
	}

	private void cancelEdit() {
		view = View.SHEET;
		editFormat = null;
		editChar = null;
		rebuildForm();
	}

	private void saveEdit() {
		if (editFormat == null) return;
		CharacterData c = new CharacterData();
		c.format = editFormat.id;
		c.file = editChar == null ? "" : editChar.file;
		for (SheetFormat.Field f : editFormat.editableFields()) {
			String raw = formValues.getOrDefault(f.id(), f.def()).trim();
			if (f.isText()) {
				c.texts.put(f.id(), raw);
			} else {
				c.values.put(f.id(), parseNumber(raw, f.def()));
			}
		}
		if (c.texts.getOrDefault("name", "").isBlank()) c.texts.put("name", "Unnamed");
		try {
			SheetLibrary.save(c);
		} catch (IOException e) {
			say("Could not save: " + e.getMessage());
			return;
		}
		charIndex = SheetLibrary.CHARACTERS.indexOf(c);
		say("Saved " + c.file);
		cancelEdit();
	}

	private void deleteEdit() {
		if (editChar == null) return;
		if (!deleteArmed) {
			deleteArmed = true;
			return;
		}
		try {
			SheetLibrary.delete(editChar);
			say("Deleted " + editChar.file);
		} catch (IOException e) {
			say("Could not delete: " + e.getMessage());
		}
		charIndex = 0;
		cancelEdit();
	}

	private static void say(String message) {
		flash = message;
		flashUntil = System.currentTimeMillis() + 4000;
	}

	private static double parseNumber(String raw, String fallback) {
		try {
			return Double.parseDouble(raw);
		} catch (NumberFormatException e) {
			try {
				return Double.parseDouble(fallback);
			} catch (NumberFormatException e2) {
				return 0;
			}
		}
	}

	private static String numberText(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
	}

	@Override
	protected void init() {
		rebuildForm();
	}

	/** (Re)creates the text boxes for the editor, filled from {@link #formValues}. */
	private void rebuildForm() {
		clearChildren();
		formItems.clear();
		formHeads.clear();
		formScroll = 0;
		if (view != View.EDITOR || editFormat == null || textRenderer == null) return;

		int w = Math.min(WIN_W, width - 8);
		int colW = (w - 20) / 2;
		int boxW = 52;
		int rowY = 0;
		String group = null;
		int col = 0;
		for (SheetFormat.Field f : editFormat.editableFields()) {
			if (!f.group().equals(group)) {
				if (col == 1) rowY += 15;
				col = 0;
				group = f.group();
				rowY += rowY == 0 ? 0 : 4;
				formHeads.add(new FormHead(group, rowY));
				rowY += 12;
			}
			boolean text = f.isText();
			if (text && col == 1) {
				rowY += 15;
				col = 0;
			}
			int relX = text || col == 0 ? 0 : colW + 8;
			int bw = text ? 200 : boxW;
			int labelW = text ? 90 : colW - boxW - 4;
			TextFieldWidget box = new TextFieldWidget(textRenderer, 0, 0, bw, 12, Text.literal(f.label()));
			box.setMaxLength(text ? 40 : 9);
			if (!text) box.setTextPredicate(t -> t.matches("-?\\d*\\.?\\d*"));
			box.setText(formValues.getOrDefault(f.id(), f.def()));
			final String id = f.id();
			box.setChangedListener(t -> formValues.put(id, t));
			addDrawableChild(box);
			formItems.add(new FormItem(f, box, relX, rowY, labelW));
			if (text) {
				rowY += 15;
			} else if (col == 0) {
				col = 1;
			} else {
				col = 0;
				rowY += 15;
			}
		}
		if (col == 1) rowY += 15;
		formHeight = rowY + 4;
	}

	private void drawEditor(DrawContext g, int x, int y, int w, int h, float delta) {
		if (editFormat == null) {
			drawFormatChooser(g, x, y, w, h);
			return;
		}
		// top strip: format name and the buttons
		g.fill(x, y, x + w, y + 18, 0xFF12151A);
		g.fill(x, y + 17, x + w, y + 18, EDGE);
		g.drawText(textRenderer, "Format: " + editFormat.name, x + 8, y + 5, GOLD, false);
		int rx = x + w - 6;
		rx -= smallButton(g, rx - tw("Save") - 8, y + 3, "Save", this::saveEdit, "Write the character to its file", 0xFF1E4A25, 0xFF3CB84A) + 4;
		rx -= smallButton(g, rx - tw("Cancel") - 6, y + 3, "Cancel", this::cancelEdit, "Throw away changes (Esc)", PANEL, 0xFF3B414C) + 4;
		if (editChar != null) {
			smallButton(g, rx - tw("Really delete?") - 6, y + 3, deleteArmed ? "Really delete?" : "Delete", this::deleteEdit,
					"Remove this character's file", deleteArmed ? 0xFF7A1C27 : PANEL, 0xFFB8323F);
		}

		int bodyY = y + 20;
		int bodyH = h - 20 - 2;
		formMaxScroll = Math.max(0, formHeight + 4 - bodyH);
		formScroll = MathHelper.clamp(formScroll, 0, formMaxScroll);

		g.enableScissor(x, bodyY, x + w, bodyY + bodyH);
		int baseX = x + 8;
		int baseY = bodyY + 4 - Math.round(formScroll);
		for (FormHead head : formHeads) {
			int hy = baseY + head.relY();
			if (hy + 10 > bodyY && hy < bodyY + bodyH) {
				g.drawText(textRenderer, head.text(), baseX, hy + 1, GOLD, false);
			}
		}
		for (FormItem item : formItems) {
			int fy = baseY + item.relY();
			boolean visible = fy >= bodyY && fy + 12 <= bodyY + bodyH;
			item.widget().visible = visible;
			if (!visible) continue;
			int fx = baseX + item.relX();
			g.drawText(textRenderer, textRenderer.trimToWidth(item.field().label(), item.labelW()), fx, fy + 2, 0xFFC9CCD2, false);
			item.widget().setX(fx + item.labelW() + 4);
			item.widget().setY(fy);
			item.widget().render(g, mouseX, mouseY, delta);
		}
		g.disableScissor();
		if (formMaxScroll > 0) {
			int barH = Math.max(8, Math.round(bodyH * bodyH / (float) (formHeight + 4)));
			int barY = bodyY + Math.round((bodyH - barH) * (formScroll / formMaxScroll));
			g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF5A616C);
		}
	}

	private void drawFormatChooser(DrawContext g, int x, int y, int w, int h) {
		g.drawText(textRenderer, "Which game is this character for?", x + 8, y + 7, GOLD, false);
		List<SheetFormat> formats = new ArrayList<>(SheetLibrary.FORMATS.values());
		int ly = y + 20;
		for (SheetFormat f : formats) {
			boolean over = hit(x + 6, ly, w - 12, 34, () -> chooseFormat(f), null);
			g.fill(x + 6, ly, x + w - 6, ly + 34, EDGE);
			g.fill(x + 7, ly + 1, x + w - 7, ly + 33, over ? PANEL_HOVER : PANEL);
			g.drawText(textRenderer, f.name, x + 12, ly + 4, 0xFFE8E6E1, false);
			var ms = g.getMatrices();
			ms.push();
			ms.translate(x + 12, ly + 16, 0);
			ms.scale(0.75f, 0.75f, 1.0f);
			int dy = 0;
			for (OrderedText line : textRenderer.wrapLines(StringVisitable.plain(f.description), (int) ((w - 30) / 0.75f))) {
				if (dy > 18) break;
				g.drawText(textRenderer, line, 0, dy, MUTED, false);
				dy += 9;
			}
			ms.pop();
			ly += 38;
			if (ly > y + h - 30) break;
		}
		smallButton(g, x + 8, y + h - 18, "Cancel", this::cancelEdit, null, PANEL, 0xFF3B414C);
	}

	// ------------------------------------------------------------------ rolling

	private void rollDice(SheetContext sc, String label, String formula) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		try {
			Expr.Roll roll = sc.plan(formula);
			if (!ClientPlayNetworking.canSend(DiceRequestPayload.ID)) {
				mc.player.sendMessage(Text.literal("[sheet] This server does not have Tactical Combat, so it can not roll dice.")
						.formatted(Formatting.RED), false);
				return;
			}
			int mode = roll.type() == DiceType.D20 && roll.count() == 1 ? rollMode : 0;
			ClientPlayNetworking.send(new DiceRequestPayload(label, roll.type(), roll.count(), roll.modifier(), mode));
		} catch (RuntimeException e) {
			mc.player.sendMessage(Text.literal("[sheet] " + label + ": " + e.getMessage()).formatted(Formatting.RED), false);
		}
	}

	// ------------------------------------------------------------------ input

	private void resetScroll() {
		java.util.Arrays.fill(scroll, 0);
	}

	private int tw(String s) {
		return textRenderer.getWidth(s);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				if (hit.contains(mx, my)) {
					hit.action.run();
					return true;
				}
			}
			int w = Math.min(WIN_W, width - 8);
			if (mx >= winX && mx < winX + w && my >= winY && my < winY + TITLE_H) {
				dragging = true;
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragging) {
			winX += (int) Math.round(dx);
			winY += (int) Math.round(dy);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		dragging = false;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (view == View.EDITOR && editFormat != null) {
			formScroll = MathHelper.clamp(formScroll - (float) vertical * 14, 0, formMaxScroll);
			return true;
		}
		SheetContext sc = view != View.SHEET ? null : sheet();
		if (sc != null) {
			SheetFormat.Page page = sc.format.pages.get(MathHelper.clamp(pageIndex, 0, sc.format.pages.size() - 1));
			float totalWeight = 0;
			for (SheetFormat.Column c : page.columns) totalWeight += c.weight;
			int w = Math.min(WIN_W, width - 8);
			int usable = w - 8 - 4 * (page.columns.size() - 1);
			int cx = winX + 4;
			for (int ci = 0; ci < page.columns.size() && ci < scroll.length; ci++) {
				int cw = Math.round(usable * page.columns.get(ci).weight / totalWeight);
				if (mx >= cx && mx < cx + cw) {
					scroll[ci] = MathHelper.clamp(scroll[ci] - (float) vertical * 14, 0, maxScroll[ci]);
					return true;
				}
				cx += cw + 4;
			}
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		boolean typing = view == View.EDITOR && getFocused() instanceof TextFieldWidget field && field.isFocused();
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && view == View.EDITOR) {
			cancelEdit();
			return true;
		}
		if (!typing && TacticalCombatClient.SHEET_KEY.matchesKey(keyCode, scanCode)) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
