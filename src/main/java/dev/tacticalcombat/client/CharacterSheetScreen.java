package dev.tacticalcombat.client;

import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.net.DiceRequestPayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.Expr;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import dev.tacticalcombat.sheet.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
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

	private static int BG = 0xFF181B20;
	private static int PANEL = 0xFF20242B;
	private static int PANEL_HOVER = 0xFF2B313A;
	private static int EDGE = 0xFF2C313A;
	private static int GOLD = 0xFFE0B84C;
	private static int MUTED = 0xFF9AA0A8;
	private static int DIM = 0xFF6F7680;
	private static int CRIMSON_A = 0xFF5C0F1B;
	private static int CRIMSON_B = 0xFF8F1D2C;
	private static int ROLL_BG = 0xFF2A2218;
	private static int ROLL_EDGE = 0xFF7A6330;

	private static void applyTheme(Theme t) {
		BG = t.get("bg");
		PANEL = t.get("panel");
		PANEL_HOVER = t.get("panel_hover");
		EDGE = t.get("edge");
		GOLD = t.get("accent");
		MUTED = t.get("muted");
		DIM = t.get("dim");
		CRIMSON_A = t.get("banner_a");
		CRIMSON_B = t.get("banner_b");
		ROLL_BG = t.get("roll_bg");
		ROLL_EDGE = t.get("roll_edge");
	}

	/** Format text may be a translation key (e.g. "mygame.skill.stealth"); anything else is shown as written. */
	private static String tr(String text) {
		if (text == null || text.isEmpty() || text.indexOf(' ') >= 0 || text.indexOf('.') < 0) return text;
		return I18n.hasTranslation(text) ? I18n.translate(text) : text;
	}

	// the window remembers where it was, which character and page was open, and the advantage mode
	private static int winX = Integer.MIN_VALUE;
	private static int winY;
	private static int charIndex;
	private static int pageIndex;
	private static int rollMode; // 0 normal, 1 advantage, 2 disadvantage
	private enum View { SHEET, FORMATS, EDITOR, ROW }

	private static View view = View.SHEET;
	// character editor state (kept static so a window resize does not lose what was typed)
	private static SheetFormat editFormat;
	private static CharacterData editChar;
	private static final Map<String, String> formValues = new LinkedHashMap<>();
	private static String flash = "";
	private static long flashUntil;
	private static boolean loaded;
	private static boolean pendingOpen;
	/** Layout mode: tabs and sections can be hidden and sections moved; the theme can be changed. */
	private static boolean customize;
	private static String editKind = SheetFormat.DEFAULT_KIND;

	private record Hit(int x, int y, int w, int h, Runnable action, Runnable alt, String tip) {
		Hit(int x, int y, int w, int h, Runnable action, String tip) {
			this(x, y, w, h, action, null, tip);
		}

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

	/** Like {@link #hit} with a second action for the right mouse button. */
	private boolean hit(int x, int y, int w, int h, Runnable action, Runnable alt, String tip) {
		hits.add(new Hit(x, y, w, h, action, alt, tip));
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	// ------------------------------------------------------------------ render

	@Override
	public void render(DrawContext g, int mx, int my, float delta) {
		mouseX = mx;
		mouseY = my;
		hits.clear();

		SheetContext themed = view == View.SHEET || view == View.ROW ? sheet() : null;
		applyTheme(SheetLibrary.theme(themed == null ? null
				: !themed.character.theme.isEmpty() ? themed.character.theme : themed.format.theme));

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
		} else if (view == View.ROW) {
			drawRowEditor(g, x, y + TITLE_H, w, h - TITLE_H, delta);
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
		if (view != View.EDITOR && view != View.ROW) {
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
					: view == View.EDITOR ? (editChar == null ? "New character" : "Edit " + editChar.displayName())
					: view == View.ROW ? (rowIndex < 0 ? "New " : "Edit ") + (rowColl == null ? "entry" : tr(rowColl.label)) : name;
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

		String title = tr(sc.template(sc.layout.titleTemplate));
		String subtitle = tr(sc.template(sc.layout.subtitleTemplate));
		var ms = g.getMatrices();
		ms.push();
		ms.translate(x + 54, y + 7, 0);
		ms.scale(1.5f, 1.5f, 1.0f);
		g.drawText(textRenderer, title, 0, 0, 0xFFFFFFFF, true);
		ms.pop();
		g.drawText(textRenderer, subtitle, x + 54, y + 22, 0xFFF0CFD2, false);

		int bx = x + 54;
		for (SheetFormat.Bar bar : sc.layout.bars) {
			double cur = sc.number(bar.value());
			double max = sc.number(bar.max());
			float frac = max > 0 && !Double.isNaN(cur) ? (float) MathHelper.clamp(cur / max, 0, 1) : 0;
			int bw = 88;

			// a bar whose value is a stored number (not a calculated one) can be changed right here
			String name = bar.value().trim().toLowerCase(java.util.Locale.ROOT);
			boolean adjustable = name.matches("[a-z_][a-z0-9_]*") && !sc.format.derived.containsKey(name);
			if (adjustable) {
				bx += adjustButton(g, sc, bar, name, "-", -1, bx, y + 34) + 1;
			}
			g.fill(bx, y + 34, bx + bw, y + 45, 0xFF05060A);
			g.fill(bx + 1, y + 35, bx + bw - 1, y + 44, 0xFF1A0B0E);
			g.fill(bx + 1, y + 35, bx + 1 + Math.round((bw - 2) * frac), y + 44, bar.color());
			double temp = bar.temp() == null ? 0 : sc.number(bar.temp());
			if (temp > 0 && max > 0) {
				// temporary HP: a blue slice after the real HP (capped at the end of the bar)
				int from = bx + 1 + Math.round((bw - 2) * frac);
				int to = Math.min(bx + bw - 1, from + Math.max(2, Math.round((bw - 2) * (float) (temp / max))));
				g.fill(from, y + 35, to, y + 44, 0xFF4DA3FF);
			}
			String label = tr(bar.label()) + " " + sc.show(bar.value(), false) + " / " + sc.show(bar.max(), false)
					+ (temp > 0 ? " +" + sc.show(bar.temp(), false) : "");
			g.drawCenteredTextWithShadow(textRenderer, label, bx + bw / 2, y + 35, 0xFFFFFFFF);
			bx += bw + 1;
			if (adjustable) {
				bx += adjustButton(g, sc, bar, name, "+", 1, bx, y + 34);
			}
			bx += 6;
		}

		int badgeX = x + w - 6;
		for (int i = sc.layout.badges.size() - 1; i >= 0; i--) {
			SheetFormat.Badge b = sc.layout.badges.get(i);
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
			g.drawCenteredTextWithShadow(textRenderer, tr(b.label()), badgeX + 18, y + 33, 0xFFF0CFD2);
			badgeX -= 2;
		}
	}

	/** The stored value name behind a bar ("hp"), or null when the bar shows something calculated. */
	private static String editableKey(SheetContext sc, String formula) {
		String name = formula.trim().toLowerCase();
		if (!name.matches("[a-z_][a-z0-9_]*") || sc.format.derived.containsKey(name)) return null;
		return name;
	}

	private static int step() {
		return hasControlDown() ? 10 : hasShiftDown() ? 5 : 1;
	}

	/** Changes a stored value (such as HP) by delta, keeps it between 0 and its maximum, and saves the character. */
	private static void adjust(SheetContext sc, String key, String maxFormula, int delta) {
		CharacterData c = sc.character;
		double cur = c.values.getOrDefault(key, sc.format.defaults.getOrDefault(key, 0.0));
		double max = sc.number(maxFormula);
		double next = cur + delta;
		next = Math.max(0, max > 0 ? Math.min(max, next) : next);
		if (next == cur) return;
		c.values.put(key, next);
		try {
			SheetLibrary.save(c);
		} catch (IOException e) {
			say("Could not save: " + e.getMessage());
		}
	}

	/** The small - / + beside a bar: click = 1, Shift = 5, Ctrl = 10. Saves the character straight away. */
	private int adjustButton(DrawContext g, SheetContext sc, SheetFormat.Bar bar, String name, String text, int direction, int x, int y) {
		int bw = 11;
		boolean over = hit(x, y, bw, 11, () -> adjustValue(sc, bar, name, direction * step()),
				tr(bar.label()) + " " + text + "1  (Shift: " + text + "5, Ctrl: " + text + "10)");
		g.fill(x, y, x + bw, y + 11, 0xFF05060A);
		g.fill(x + 1, y + 1, x + bw - 1, y + 10, over ? CRIMSON_B : CRIMSON_A);
		g.drawCenteredTextWithShadow(textRenderer, text, x + bw / 2, y + 2, 0xFFFFFFFF);
		return bw;
	}

	/** Damage (negative) is soaked up by temporary HP first, when the bar has them. */
	private void adjustValue(SheetContext sc, SheetFormat.Bar bar, String name, int delta) {
		CharacterData c = sc.character;
		if (delta < 0 && bar.temp() != null) {
			String tempKey = editableKey(sc, bar.temp());
			double temp = tempKey == null ? 0 : c.values.getOrDefault(tempKey, sc.format.defaults.getOrDefault(tempKey, 0.0));
			if (temp > 0) {
				double soaked = Math.min(temp, -delta);
				c.values.put(tempKey, temp - soaked);
				delta += (int) soaked;
				if (delta == 0) {
					store(c);
					return;
				}
			}
		}
		adjust(sc, name, bar.max(), delta);
	}

	private static void store(CharacterData c) {
		try {
			SheetLibrary.save(c);
		} catch (IOException e) {
			say("Could not save: " + e.getMessage());
		}
	}

	private static double stored(SheetContext sc, String key) {
		return sc.character.values.getOrDefault(key, sc.format.defaults.getOrDefault(key, 0.0));
	}

	private static void setStored(SheetContext sc, String key, double value) {
		sc.character.values.put(key, value);
		store(sc.character);
	}

	/** Pages shown now: enabled ones that are not hidden (hidden ones still show, dimmed, in layout mode). */
	private List<SheetFormat.Page> pages(SheetContext sc) {
		List<SheetFormat.Page> out = new ArrayList<>();
		for (SheetFormat.Page p : sc.layout.pages) {
			if (!sc.enabled(p.enabled)) continue;
			if (!customize && sc.character.hidden.contains("page:" + p.id)) continue;
			out.add(p);
		}
		return out;
	}

	/** Sections of a column in the character's chosen order, minus hidden / disabled ones. */
	private List<SheetFormat.Section> sections(SheetContext sc, SheetFormat.Page page, int colIndex) {
		SheetFormat.Column col = page.columns.get(colIndex);
		List<SheetFormat.Section> ordered = new ArrayList<>(col.sections);
		List<String> wanted = sc.character.order.get(page.id + "/" + colIndex);
		if (wanted != null) {
			ordered.sort(java.util.Comparator.comparingInt(sec -> {
				int i = wanted.indexOf(sec.id);
				return i < 0 ? Integer.MAX_VALUE : i;
			}));
		}
		List<SheetFormat.Section> out = new ArrayList<>();
		for (SheetFormat.Section sec : ordered) {
			if (!sc.enabled(sec.enabled)) continue;
			if (!customize && sc.character.hidden.contains("section:" + page.id + "/" + sec.id)) continue;
			out.add(sec);
		}
		return out;
	}

	private void toggleHidden(SheetContext sc, String key) {
		if (!sc.character.hidden.remove(key)) sc.character.hidden.add(key);
		store(sc.character);
	}

	private void moveSection(SheetContext sc, SheetFormat.Page page, int colIndex, SheetFormat.Section section, int dir) {
		List<SheetFormat.Section> all = sections(sc, page, colIndex);
		int i = all.indexOf(section);
		int j = i + dir;
		if (i < 0 || j < 0 || j >= all.size()) return;
		java.util.Collections.swap(all, i, j);
		List<String> ids = new ArrayList<>();
		for (SheetFormat.Section sec : all) ids.add(sec.id);
		sc.character.order.put(page.id + "/" + colIndex, ids);
		store(sc.character);
	}

	private void drawTabs(DrawContext g, SheetContext sc, int x, int y, int w) {
		g.fill(x, y, x + w, y + TAB_H, 0xFF12151A);
		g.fill(x, y + TAB_H - 1, x + w, y + TAB_H, EDGE);
		List<SheetFormat.Page> pages = pages(sc);
		pageIndex = MathHelper.clamp(pageIndex, 0, Math.max(0, pages.size() - 1));

		int tx = x + 6;
		for (int i = 0; i < pages.size(); i++) {
			final int idx = i;
			SheetFormat.Page page = pages.get(i);
			boolean hidden = sc.character.hidden.contains("page:" + page.id);
			String t = tr(page.title);
			int bw = tw(t) + 14 + (customize ? 9 : 0);
			boolean over = hit(tx, y, bw - (customize ? 9 : 0), TAB_H, () -> { pageIndex = idx; resetScroll(); }, null);
			boolean on = i == pageIndex;
			g.drawText(textRenderer, t, tx + 7, y + 4, hidden ? DIM : on ? 0xFFFFFFFF : over ? 0xFFC9CCD2 : MUTED, false);
			if (on) g.fill(tx + 2, y + TAB_H - 3, tx + bw - 2 - (customize ? 9 : 0), y + TAB_H - 1, GOLD);
			if (customize) {
				eye(g, sc, "page:" + page.id, tx + bw - 11, y + 4, hidden, "Show / hide this tab");
			}
			tx += bw;
		}

		// right side: the Layout button (and, in layout mode, the theme)
		int rx = x + w - 6;
		rx -= smallButton(g, rx - tw("Layout") - 6, y + 2, "Layout", () -> customize = !customize,
				"Hide tabs and sections, move sections, change the theme", customize ? GOLD : PANEL, 0xFF3B414C) + 2;
		if (customize) {
			Theme current = SheetLibrary.theme(!sc.character.theme.isEmpty() ? sc.character.theme : sc.format.theme);
			String label = "Theme: " + current.name;
			smallButton(g, rx - tw(label) - 6, y + 2, label, () -> cycleTheme(sc, current), "Next colour theme", PANEL, 0xFF3B414C);
		}
	}

	private void cycleTheme(SheetContext sc, Theme current) {
		List<String> ids = new ArrayList<>(SheetLibrary.THEMES.keySet());
		if (ids.isEmpty()) return;
		sc.character.theme = ids.get((ids.indexOf(current.id) + 1) % ids.size());
		store(sc.character);
	}

	/** A small eye: filled = shown, hollow = hidden. */
	private void eye(DrawContext g, SheetContext sc, String key, int x, int y, boolean hidden, String tip) {
		hit(x - 1, y - 1, 9, 9, () -> toggleHidden(sc, key), tip);
		g.fill(x, y, x + 7, y + 7, hidden ? DIM : GOLD);
		g.fill(x + 1, y + 1, x + 6, y + 6, hidden ? BG : PANEL);
		if (!hidden) g.fill(x + 2, y + 2, x + 5, y + 5, GOLD);
	}

	private void drawBody(DrawContext g, SheetContext sc, int x, int y, int w, int h) {
		List<SheetFormat.Page> pages = pages(sc);
		if (pages.isEmpty()) {
			g.drawText(textRenderer, "Every tab is hidden. Press Layout, then the eye on a tab.", x + 8, y + 10, MUTED, false);
			return;
		}
		SheetFormat.Page page = pages.get(MathHelper.clamp(pageIndex, 0, pages.size() - 1));
		float totalWeight = 0;
		for (SheetFormat.Column c : page.columns) totalWeight += c.weight;
		int gap = 4;
		int usable = w - 8 - gap * (page.columns.size() - 1);
		int cx = x + 4;
		for (int ci = 0; ci < page.columns.size() && ci < scroll.length; ci++) {
			SheetFormat.Column col = page.columns.get(ci);
			int cw = Math.round(usable * col.weight / totalWeight);
			maxScroll[ci] = drawColumn(g, sc, page, ci, cx, y + 3, cw, h - 3);
			cx += cw + gap;
		}
	}

	/** Draws one scrollable column; returns how far it can scroll. */
	private float drawColumn(DrawContext g, SheetContext sc, SheetFormat.Page page, int ci, int x, int y, int w, int h) {
		scroll[ci] = MathHelper.clamp(scroll[ci], 0, maxScroll[ci]);
		g.enableScissor(x, y, x + w, y + h);
		int cy = y - Math.round(scroll[ci]);
		int start = cy;
		for (SheetFormat.Section sec : sections(sc, page, ci)) {
			String hideKey = "section:" + page.id + "/" + sec.id;
			boolean hidden = sc.character.hidden.contains(hideKey);
			if (!sec.title.isEmpty() || customize) {
				if (cy + 11 > y && cy < y + h) {
					g.drawText(textRenderer, tr(sec.title.isEmpty() ? sec.id : sec.title), x + 1, cy + 1, hidden ? DIM : GOLD, false);
					if (customize) {
						int bx = x + w - 3 - 28;
						eye(g, sc, hideKey, bx, cy + 2, hidden, "Show / hide this section");
						smallArrow(g, "^", () -> moveSection(sc, page, ci, sec, -1), x + w - 3 - 17, cy + 1, "Move up");
						smallArrow(g, "v", () -> moveSection(sc, page, ci, sec, 1), x + w - 3 - 9, cy + 1, "Move down");
					}
				}
				cy += 11;
			}
			if (hidden) {
				cy += 4;
				continue;
			}
			for (SheetFormat.Item it : sec.items) {
				if (!sc.enabled(it.enabled)) continue;
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

	private void smallArrow(DrawContext g, String text, Runnable action, int x, int y, String tip) {
		boolean over = hit(x, y, 8, 9, action, tip);
		g.drawText(textRenderer, text, x + 1, y + 1, over ? 0xFFFFFFFF : MUTED, false);
	}

	/** One row of a section. Returns its height. */
	private int drawItem(DrawContext g, SheetContext sc, SheetFormat.Item it, int x, int y, int w, int clipTop, int clipBottom) {
		if (it.widget.equals("pips") || it.widget.equals("counter")) {
			return drawTracker(g, sc, it, x, y, w, clipTop, clipBottom);
		}
		if (it.widget.equals("rollmode")) {
			return drawRollMode(g, it, x, y, w, clipTop, clipBottom);
		}
		if (it.widget.equals("table")) {
			return drawTable(g, sc, it, x, y, w, clipTop, clipBottom);
		}
		boolean hasText = !it.text.isEmpty();
		List<OrderedText> lines = hasText
				? textRenderer.wrapLines(StringVisitable.plain(tr(it.text)), (int) ((w - 8) / 0.75f)) : List.of();
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
			String t = tr(b.before) + (b.value != null ? sc.show(b.value, b.signed) : "")
					+ (b.label.isEmpty() ? "" : (b.value != null || !b.before.isEmpty() ? " " : "") + tr(b.label));
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
			rowHover = hit(x, y, w, h, () -> rollDice(sc, label, roll), "Roll " + tr(label));
		}
		g.fill(x, y, x + w, y + h, rowHover ? PANEL_HOVER : PANEL);
		g.fill(x, y, x + 1, y + h, EDGE);

		int tx = x + 4;
		if (it.widget.equals("cycle") && it.store != null) {
			// a proficiency-style marker the player can step through (e.g. none / half / full / expertise)
			double now = stored(sc, it.store);
			hit(x + 1, y, 11, Math.min(h, 13), () -> {
				int at = 0;
				for (int k = 0; k < it.cycle.length; k++) if (Math.abs(it.cycle[k] - now) < 1.0E-6) at = k;
				setStored(sc, it.store, it.cycle[(at + 1) % it.cycle.length]);
			}, "Click to change (" + numberText(now) + ")");
			double top = it.cycle[it.cycle.length - 1];
			g.fill(x + 3, y + 4, x + 10, y + 11, now > 0 ? GOLD : 0xFF5A616C);
			g.fill(x + 4, y + 5, x + 9, y + 10, PANEL);
			if (now >= top && now > 0) g.fill(x + 4, y + 5, x + 9, y + 10, GOLD);
			else if (now > 0 && now < top) g.fill(x + 4, y + 5, x + 9, y + 5 + Math.max(2, Math.round(5 * (float) (now / top))), GOLD);
			if (now > 1) g.fill(x + 5, y + 6, x + 8, y + 9, 0xFF14161A);
			tx = x + 14;
		} else if (it.mark != null) {
			boolean on = sc.number(it.mark) > 0;
			g.fill(x + 3, y + 4, x + 8, y + 9, on ? GOLD : 0xFF5A616C);
			if (!on) g.fill(x + 4, y + 5, x + 7, y + 8, PANEL);
			tx = x + 11;
		}

		String label = textRenderer.trimToWidth(tr(it.label), Math.max(10, free - tx - (it.value != null ? 28 : 4)));
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
			g.drawText(textRenderer, tr(it.sub), 0, 0, MUTED, false);
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
			boolean over = b.roll != null && hit(bxs[i], y + 1, bws[i], 11, () -> rollDice(sc, b.rollLabel, b.roll), "Roll " + tr(b.rollLabel));
			g.fill(bxs[i], y + 1, bxs[i] + bws[i], y + 12, ROLL_EDGE);
			g.fill(bxs[i] + 1, y + 2, bxs[i] + bws[i] - 1, y + 11, over ? 0xFF3A2A10 : ROLL_BG);
			g.drawText(textRenderer, texts[i], bxs[i] + 3, y + 3, 0xFFFFFFFF, false);
		}
		return h;
	}

	// ------------------------------------------------------------------ collections (tables)

	private static String rowName(SheetFormat.Collection c, CharacterData.Row r) {
		SheetFormat.Col nc = c.nameCol();
		String n = nc == null ? "" : r.texts.getOrDefault(nc.id, "");
		return n.isBlank() ? tr(c.label) : n;
	}

	private static void setRowValue(SheetContext sc, CharacterData.Row row, String col, double value) {
		row.values.put(col, value);
		store(sc.character);
	}

	/** Dice text of a "dice" column with its optional modifier formula, ready for Expr.roll. */
	private static String diceFormula(SheetFormat.Col col, CharacterData.Row row) {
		String text = row.texts.getOrDefault(col.id, "").trim();
		if (text.isEmpty()) return null;
		return col.modifier == null ? text : text + " + (" + col.modifier + ")";
	}

	private void rollRowDice(SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, SheetFormat.Col col, String formula) {
		String label = tr(col.rollLabel).replace("{name}", rowName(coll, row));
		rollPlan(sc, label, () -> sc.plan(coll, row, formula));
	}

	private int drawTable(DrawContext g, SheetContext sc, SheetFormat.Item it, int x, int y, int w, int clipTop, int clipBottom) {
		SheetFormat.Collection coll = sc.format.collections.get(it.collection);
		if (coll == null) return 13;
		List<CharacterData.Row> rows = sc.character.collections.getOrDefault(coll.id, List.of());
		// the table's own columns; "detail" and "note" columns go on a small second line instead
		List<SheetFormat.Col> main = new ArrayList<>();
		for (SheetFormat.Col c : coll.columns) if (!c.detail && !c.type.equals("note")) main.add(c);
		int n = main.size();
		int editW = 14;
		int gap = 3;
		float total = 0;
		for (SheetFormat.Col c : main) total += c.width;
		int avail = w - 8 - editW - gap * Math.max(0, n - 1);
		int[] cx = new int[n];
		int[] cw = new int[n];
		int px = x + 4;
		for (int i = 0; i < n; i++) {
			cw[i] = Math.max(8, Math.round(avail * main.get(i).width / total));
			cx[i] = px;
			px += cw[i] + gap;
		}

		int cy = y;
		// header
		if (cy + 9 > clipTop && cy < clipBottom) {
			var ms = g.getMatrices();
			for (int i = 0; i < n; i++) {
				ms.push();
				ms.translate(cx[i], cy + 2, 0);
				ms.scale(0.75f, 0.75f, 1.0f);
				g.drawText(textRenderer, textRenderer.trimToWidth(tr(main.get(i).label), (int) (cw[i] / 0.75f)), 0, 0, DIM, false);
				ms.pop();
			}
		}
		cy += 9;

		for (int ri = 0; ri < rows.size(); ri++) {
			final int index = ri;
			CharacterData.Row row = rows.get(ri);
			StringBuilder note = new StringBuilder();
			for (SheetFormat.Col c : coll.columns) {
				String t = c.type.equals("note") ? row.texts.getOrDefault(c.id, "").trim() : c.detail ? detailText(sc, coll, row, c) : "";
				if (!t.isEmpty()) note.append(note.length() > 0 ? (c.type.equals("note") ? "  |  " : "  -  ") : "").append(t);
			}
			List<OrderedText> noteLines = note.length() == 0 ? List.of()
					: textRenderer.wrapLines(StringVisitable.plain(tr(note.toString())), (int) ((w - 12) / 0.75f));
			int rh = 13 + (noteLines.isEmpty() ? 0 : noteLines.size() * 7 + 2);
			if (cy + rh > clipTop && cy < clipBottom) {
				g.fill(x, cy, x + w, cy + rh, PANEL);
				g.fill(x, cy, x + 1, cy + rh, EDGE);
				for (int i = 0; i < n; i++) drawCell(g, sc, coll, row, main.get(i), cx[i], cw[i], cy);
				if (!noteLines.isEmpty()) {
					var ms = g.getMatrices();
					ms.push();
					ms.translate(x + 4, cy + 13, 0);
					ms.scale(0.75f, 0.75f, 1.0f);
					int ly = 0;
					for (OrderedText line : noteLines) {
						g.drawText(textRenderer, line, 0, ly, MUTED, false);
						ly += 9;
					}
					ms.pop();
				}
				int ex = x + w - editW - 2;
				boolean over = hit(ex, cy + 1, editW, 11, () -> startRow(sc, coll, index), "Edit or delete this entry");
				g.fill(ex, cy + 1, ex + editW, cy + 12, EDGE);
				g.fill(ex + 1, cy + 2, ex + editW - 1, cy + 11, over ? PANEL_HOVER : PANEL);
				g.drawCenteredTextWithShadow(textRenderer, "...", ex + editW / 2, cy + 2, 0xFFFFFFFF);
			}
			cy += rh + 1;
		}

		// add button
		if (cy + 13 > clipTop && cy < clipBottom) {
			String add = "+ " + tr(coll.addLabel);
			int bw = tw(add) + 8;
			boolean over = hit(x, cy, bw, 12, () -> startRow(sc, coll, -1), "Add an entry to " + tr(coll.label));
			g.fill(x, cy, x + bw, cy + 12, EDGE);
			g.fill(x + 1, cy + 1, x + bw - 1, cy + 11, over ? PANEL_HOVER : PANEL);
			g.drawText(textRenderer, add, x + 4, cy + 2, GOLD, false);
			if (rows.isEmpty()) g.drawText(textRenderer, "Nothing here yet", x + bw + 6, cy + 2, DIM, false);
			// footer totals on the right
			int fx = x + w - 2;
			for (int i = coll.footers.size() - 1; i >= 0; i--) {
				SheetFormat.Footer f = coll.footers.get(i);
				String v = sc.show(f.value(), false);
				String t = tr(f.label()) + " " + v;
				boolean bad = false;
				if (f.max() != null) {
					double mx = sc.number(f.max());
					t += " / " + SheetContext.format(mx, false);
					bad = !Double.isNaN(mx) && sc.number(f.value()) > mx;
				}
				fx -= tw(t);
				g.drawText(textRenderer, t, fx, cy + 2, bad ? 0xFFFF6B6B : MUTED, false);
				fx -= 8;
			}
		}
		cy += 13;
		return cy - y;
	}

	/** Short text for a detail column ("STR", "Prof", "+2 Bonus"), or empty when there is nothing worth saying. */
	private static String detailText(SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, SheetFormat.Col col) {
		switch (col.type) {
			case "toggle":
				return row.values.getOrDefault(col.id, col.def) > 0 ? tr(col.label) : "";
			case "choice": {
				int count = col.options.size();
				return tr(col.options.get(Math.floorMod((int) Math.round(row.values.getOrDefault(col.id, col.def)), count)));
			}
			case "number": {
				double v = row.values.getOrDefault(col.id, col.def);
				return v == 0 ? "" : SheetContext.format(v, col.signed) + " " + tr(col.label);
			}
			case "computed":
				return tr(col.label) + " " + SheetContext.format(sc.number(coll, row, col.value), col.signed);
			case "text":
				return row.texts.getOrDefault(col.id, "").trim();
			default:
				return "";
		}
	}

	private void drawCell(DrawContext g, SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, SheetFormat.Col col,
			int x, int w, int y) {
		int white = 0xFFFFFFFF;
		switch (col.type) {
			case "text" -> {
				String t = row.texts.getOrDefault(col.id, "");
				g.drawText(textRenderer, textRenderer.trimToWidth(tr(t), w), x, y + 3, 0xFFE8E6E1, false);
			}
			case "number" -> {
				double v = row.values.getOrDefault(col.id, col.def);
				boolean over = hit(x, y, w, 13, () -> setRowValue(sc, row, col.id, v + step()),
						() -> setRowValue(sc, row, col.id, v - step()), tr(col.label) + ": click +, right-click - (Shift 5, Ctrl 10)");
				String t = SheetContext.format(v, col.signed);
				g.drawText(textRenderer, textRenderer.trimToWidth(t, w), x, y + 3, over ? GOLD : white, false);
			}
			case "toggle" -> {
				boolean on = row.values.getOrDefault(col.id, col.def) > 0;
				hit(x, y, Math.min(w, 14), 13, () -> setRowValue(sc, row, col.id, on ? 0 : 1), tr(col.label) + (on ? ": yes" : ": no"));
				g.fill(x + 1, y + 3, x + 8, y + 10, on ? GOLD : 0xFF5A616C);
				if (!on) g.fill(x + 2, y + 4, x + 7, y + 9, PANEL);
			}
			case "choice" -> {
				int count = col.options.size();
				int cur = Math.floorMod((int) Math.round(row.values.getOrDefault(col.id, col.def)), count);
				boolean over = hit(x, y, w, 13, () -> setRowValue(sc, row, col.id, (cur + 1) % count),
						() -> setRowValue(sc, row, col.id, (cur + count - 1) % count), tr(col.label) + ": click for next, right-click for previous");
				g.drawText(textRenderer, textRenderer.trimToWidth(tr(col.options.get(cur)), w), x, y + 3, over ? GOLD : white, false);
			}
			case "computed" -> {
				double v = sc.number(coll, row, col.value);
				boolean bad = Double.isNaN(v);
				g.drawText(textRenderer, textRenderer.trimToWidth(SheetContext.format(v, col.signed), w), x, y + 3, bad ? 0xFFFF6B6B : white, false);
				if (bad) hit(x, y, w, 13, () -> {}, sc.error(coll, row, col.value));
			}
			case "roll" -> {
				double v = sc.number(coll, row, col.value == null ? "0" : col.value);
				String t = col.value == null ? "Roll" : SheetContext.format(v, col.signed);
				int bw = Math.min(w, tw(t) + 8);
				boolean over = hit(x, y + 1, bw, 11, () -> rollRowDice(sc, coll, row, col, col.roll), "Roll " + tr(col.rollLabel).replace("{name}", rowName(coll, row)));
				g.fill(x, y + 1, x + bw, y + 12, ROLL_EDGE);
				g.fill(x + 1, y + 2, x + bw - 1, y + 11, over ? 0xFF3A2A10 : ROLL_BG);
				g.drawText(textRenderer, textRenderer.trimToWidth(t, bw - 4), x + 4, y + 3, white, false);
			}
			case "dice" -> {
				String formula = diceFormula(col, row);
				if (formula == null) {
					g.drawText(textRenderer, "-", x, y + 3, DIM, false);
					return;
				}
				String t = row.texts.getOrDefault(col.id, "").trim();
				if (col.modifier != null) {
					double m = sc.number(coll, row, col.modifier);
					if (!Double.isNaN(m) && Math.round(m) != 0) t += SheetContext.format(m, true);
				}
				int bw = Math.min(w, tw(t) + 8);
				boolean over = hit(x, y + 1, bw, 11, () -> rollRowDice(sc, coll, row, col, formula), "Roll " + tr(col.rollLabel).replace("{name}", rowName(coll, row)));
				g.fill(x, y + 1, x + bw, y + 12, ROLL_EDGE);
				g.fill(x + 1, y + 2, x + bw - 1, y + 11, over ? 0xFF3A2A10 : ROLL_BG);
				g.drawText(textRenderer, textRenderer.trimToWidth(t, bw - 4), x + 4, y + 3, white, false);
			}
			default -> {
				// "note" columns are drawn under the row
			}
		}
	}

	// ------------------------------------------------------------------ row editor

	private static SheetFormat.Collection rowColl;
	private static int rowIndex = -1;
	private static CharacterData.Row rowDraft;
	private static CharacterData rowOwner;

	private record RowBox(SheetFormat.Col col, TextFieldWidget widget, int relY) {}

	private final List<RowBox> rowBoxes = new ArrayList<>();

	private void startRow(SheetContext sc, SheetFormat.Collection coll, int index) {
		rowColl = coll;
		rowIndex = index;
		rowOwner = sc.character;
		rowDraft = index >= 0 ? sc.character.rows(coll.id).get(index).copy() : new CharacterData.Row();
		deleteArmed = false;
		view = View.ROW;
		rebuildForm();
	}

	private void cancelRow() {
		view = View.SHEET;
		rowColl = null;
		rowDraft = null;
		rebuildForm();
	}

	private void saveRow() {
		if (rowColl == null || rowDraft == null || rowOwner == null) return;
		for (SheetFormat.Col col : rowColl.columns) {
			if (!col.type.equals("dice")) continue;
			String t = rowDraft.texts.getOrDefault(col.id, "").trim();
			if (t.isEmpty()) continue;
			try {
				Expr.roll(t, n -> 0.0);
			} catch (RuntimeException e) {
				say(tr(col.label) + ": '" + t + "' is not dice (try 1d8 or 2d6+1)");
				return;
			}
		}
		SheetFormat.Col nc = rowColl.nameCol();
		if (nc != null && rowDraft.texts.getOrDefault(nc.id, "").isBlank()) rowDraft.texts.put(nc.id, "New entry");
		List<CharacterData.Row> rows = rowOwner.rows(rowColl.id);
		if (rowIndex >= 0 && rowIndex < rows.size()) rows.set(rowIndex, rowDraft);
		else rows.add(rowDraft);
		store(rowOwner);
		cancelRow();
	}

	private void deleteRow() {
		if (rowColl == null || rowOwner == null) return;
		if (!deleteArmed) {
			deleteArmed = true;
			return;
		}
		List<CharacterData.Row> rows = rowOwner.rows(rowColl.id);
		if (rowIndex >= 0 && rowIndex < rows.size()) rows.remove(rowIndex);
		store(rowOwner);
		cancelRow();
	}

	private void buildRowForm() {
		rowBoxes.clear();
		if (rowColl == null || rowDraft == null) return;
		int relY = 0;
		for (SheetFormat.Col col : rowColl.columns) {
			if (!col.isStored()) continue;
			TextFieldWidget box = null;
			if (col.isText() || col.type.equals("number")) {
				boolean number = col.type.equals("number");
				box = new TextFieldWidget(textRenderer, 0, 0, number ? 52 : 220, 12, Text.literal(col.label));
				box.setMaxLength(number ? 9 : col.type.equals("note") ? 400 : col.type.equals("dice") ? 24 : 60);
				if (number) box.setTextPredicate(t -> t.matches("-?\\d*\\.?\\d*"));
				box.setText(number ? numberText(rowDraft.values.getOrDefault(col.id, col.def)) : rowDraft.texts.getOrDefault(col.id, ""));
				final String id = col.id;
				if (number) {
					box.setChangedListener(t -> {
						try {
							rowDraft.values.put(id, Double.parseDouble(t));
						} catch (NumberFormatException e) {
							rowDraft.values.remove(id);
						}
					});
				} else {
					box.setChangedListener(t -> rowDraft.texts.put(id, t));
				}
				addDrawableChild(box);
			}
			rowBoxes.add(new RowBox(col, box, relY));
			relY += 17;
		}
	}

	private void drawRowEditor(DrawContext g, int x, int y, int w, int h, float delta) {
		if (rowColl == null || rowDraft == null) {
			cancelRow();
			return;
		}
		g.fill(x, y, x + w, y + 18, 0xFF12151A);
		g.fill(x, y + 17, x + w, y + 18, EDGE);
		g.drawText(textRenderer, tr(rowColl.label), x + 8, y + 5, GOLD, false);
		int rx = x + w - 6;
		rx -= smallButton(g, rx - tw("Save") - 8, y + 3, "Save", this::saveRow, "Keep this entry", 0xFF1E4A25, 0xFF3CB84A) + 4;
		rx -= smallButton(g, rx - tw("Cancel") - 6, y + 3, "Cancel", this::cancelRow, "Throw away changes (Esc)", PANEL, 0xFF3B414C) + 4;
		if (rowIndex >= 0) {
			smallButton(g, rx - tw("Really delete?") - 6, y + 3, deleteArmed ? "Really delete?" : "Delete", this::deleteRow,
					"Remove this entry", deleteArmed ? 0xFF7A1C27 : PANEL, 0xFFB8323F);
		}

		int baseX = x + 8;
		int baseY = y + 26;
		for (RowBox rb : rowBoxes) {
			SheetFormat.Col col = rb.col();
			int fy = baseY + rb.relY();
			g.drawText(textRenderer, textRenderer.trimToWidth(tr(col.label), 90), baseX, fy + 2, 0xFFC9CCD2, false);
			int fx = baseX + 96;
			if (rb.widget() != null) {
				rb.widget().setX(fx);
				rb.widget().setY(fy);
				rb.widget().render(g, mouseX, mouseY, delta);
				if (col.type.equals("dice")) g.drawText(textRenderer, "e.g. 1d8", fx + 226, fy + 2, DIM, false);
				if (col.type.equals("note")) g.drawText(textRenderer, "notes", fx + 226, fy + 2, DIM, false);
			} else if (col.type.equals("toggle")) {
				boolean on = rowDraft.values.getOrDefault(col.id, col.def) > 0;
				boolean over = hit(fx, fy, 60, 12, () -> rowDraft.values.put(col.id, on ? 0.0 : 1.0), null);
				g.fill(fx, fy + 1, fx + 10, fy + 11, on ? GOLD : 0xFF5A616C);
				if (!on) g.fill(fx + 1, fy + 2, fx + 9, fy + 10, PANEL);
				g.drawText(textRenderer, on ? "Yes" : "No", fx + 15, fy + 2, over ? GOLD : 0xFFE8E6E1, false);
			} else if (col.type.equals("choice")) {
				int count = col.options.size();
				int cur = Math.floorMod((int) Math.round(rowDraft.values.getOrDefault(col.id, col.def)), count);
				int bw = 90;
				boolean over = hit(fx, fy, bw, 12, () -> rowDraft.values.put(col.id, (double) ((cur + 1) % count)),
						() -> rowDraft.values.put(col.id, (double) ((cur + count - 1) % count)), "Click for next, right-click for previous");
				g.fill(fx, fy, fx + bw, fy + 12, EDGE);
				g.fill(fx + 1, fy + 1, fx + bw - 1, fy + 11, over ? PANEL_HOVER : PANEL);
				g.drawText(textRenderer, textRenderer.trimToWidth(tr(col.options.get(cur)), bw - 6), fx + 4, fy + 2, 0xFFFFFFFF, false);
			}
		}
		// the formulas behind this entry's calculated and rolled columns, for reading only
		int fy = baseY + rowBoxes.size() * 17 + 4;
		boolean titled = false;
		for (SheetFormat.Col col : rowColl.columns) {
			String formula = switch (col.type) {
				case "roll" -> col.roll;
				case "computed" -> col.value;
				case "dice" -> col.modifier == null ? null : "dice + " + col.modifier;
				default -> null;
			};
			if (formula == null) continue;
			if (!titled) {
				g.fill(x + 8, fy, x + w - 8, fy + 1, EDGE);
				g.drawText(textRenderer, "FORMULAS", x + 8, fy + 5, GOLD, false);
				fy += 17;
				titled = true;
			}
			var ms = g.getMatrices();
			ms.push();
			ms.translate(x + 8, fy, 0);
			ms.scale(0.75f, 0.75f, 1.0f);
			int ly = 0;
			String text = tr(col.label) + ": " + formula;
			for (OrderedText line : textRenderer.wrapLines(StringVisitable.plain(text), (int) ((w - 16) / 0.75f))) {
				g.drawText(textRenderer, line, 0, ly, MUTED, false);
				ly += 9;
			}
			ms.pop();
			fy += (int) Math.ceil(ly * 0.75f) + 3;
			if (fy > y + h - 14) break;
		}
		boolean flashing = System.currentTimeMillis() < flashUntil;
		if (flashing) g.drawText(textRenderer, flash, x + 8, y + h - 12, 0xFFFF6B6B, false);
	}

	/** Pips (spell slots, inspiration, death saves...) and counters: rows that edit a stored number. */
	private int drawTracker(DrawContext g, SheetContext sc, SheetFormat.Item it, int x, int y, int w, int clipTop, int clipBottom) {
		int h = 13;
		if (y + h <= clipTop || y >= clipBottom || it.store == null) return h;
		g.fill(x, y, x + w, y + h, PANEL);
		g.fill(x, y, x + 1, y + h, EDGE);
		double max = it.max == null ? 0 : sc.number(it.max);
		double now = stored(sc, it.store);
		g.drawText(textRenderer, textRenderer.trimToWidth(tr(it.label), w - 70), x + 4, y + 3, 0xFFE8E6E1, false);

		if (it.widget.equals("pips")) {
			int n = (int) Math.max(0, Math.min(12, Double.isNaN(max) ? 0 : max));
			int px = x + w - 4 - n * 9;
			for (int i = 0; i < n; i++) {
				final int idx = i;
				boolean filled = i < now;
				hit(px + i * 9, y + 2, 9, 9, () -> setStored(sc, it.store, idx + 1 == (int) now ? idx : idx + 1), tr(it.label) + ": " + numberText(now) + " / " + n);
				g.fill(px + i * 9, y + 3, px + i * 9 + 7, y + 10, it.color);
				g.fill(px + i * 9 + 1, y + 4, px + i * 9 + 6, y + 9, filled ? it.color : PANEL);
			}
		} else {
			int bx = x + w - 3;
			String shown = numberText(now) + (max > 0 ? " / " + numberText(max) : "");
			bx -= smallStep(g, sc, it, "+", 1, bx - 11, y + 1);
			bx -= tw(shown) + 6;
			g.drawText(textRenderer, shown, bx + 3, y + 3, 0xFFFFFFFF, true);
			smallStep(g, sc, it, "-", -1, bx - 11, y + 1);
		}
		return h;
	}

	/** True when the open sheet offers the Normal / Advantage / Disadvantage control (a format's choice, not the window's). */
	private static boolean hasRollMode(SheetContext sc) {
		for (SheetFormat.Page p : sc.layout.pages) for (SheetFormat.Column c : p.columns) for (SheetFormat.Section sec : c.sections) {
			for (SheetFormat.Item i : sec.items) if (i.widget.equals("rollmode")) return true;
		}
		return false;
	}

	/** Roll mode row: Normal / Advantage / Disadvantage for single d20 rolls. */
	private int drawRollMode(DrawContext g, SheetFormat.Item it, int x, int y, int w, int clipTop, int clipBottom) {
		int h = 13;
		if (y + h <= clipTop || y >= clipBottom) return h;
		g.fill(x, y, x + w, y + h, PANEL);
		g.fill(x, y, x + 1, y + h, EDGE);
		g.drawText(textRenderer, textRenderer.trimToWidth(tr(it.label), Math.max(10, w - 80)), x + 4, y + 3, 0xFFE8E6E1, false);
		String[] names = {"Norm", "Adv", "Dis"};
		String[] tips = {"Roll d20s normally", "Advantage: roll two d20, keep the higher", "Disadvantage: roll two d20, keep the lower"};
		int bx = x + w - 3;
		for (int i = 2; i >= 0; i--) {
			final int mode = i;
			int bw = tw(names[i]) + 6;
			bx -= bw;
			boolean over = hit(bx, y + 1, bw, 11, () -> rollMode = mode, tips[i]);
			boolean on = rollMode == i;
			g.fill(bx, y + 1, bx + bw, y + 12, on ? GOLD : EDGE);
			g.fill(bx + 1, y + 2, bx + bw - 1, y + 11, on ? GOLD : over ? PANEL_HOVER : PANEL);
			g.drawText(textRenderer, names[i], bx + 3, y + 3, on ? 0xFF14161A : 0xFFC9CCD2, false);
			bx -= 1;
		}
		return h;
	}

	private int smallStep(DrawContext g, SheetContext sc, SheetFormat.Item it, String text, int direction, int x, int y) {
		boolean over = hit(x, y, 11, 11, () -> adjust(sc, it.store, it.max == null ? "0" : it.max, direction * step()),
				"Shift: 5, Ctrl: 10");
		g.fill(x, y, x + 11, y + 11, EDGE);
		g.fill(x + 1, y + 1, x + 10, y + 10, over ? PANEL_HOVER : PANEL);
		g.drawCenteredTextWithShadow(textRenderer, text, x + 5, y + 2, 0xFFFFFFFF);
		return 11;
	}

	private void drawFooter(DrawContext g, int x, int y, int w) {
		g.fill(x, y, x + w, y + FOOT_H, 0xFF0F1114);
		g.fill(x, y, x + w, y + 1, EDGE);
		boolean flashing = System.currentTimeMillis() < flashUntil;
		g.drawText(textRenderer, flashing ? flash : "Click a value or button to roll. +/-: Shift = 5, Ctrl = 10.", x + 6, y + 2,
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
			g.drawText(textRenderer, f.layouts.size() + " sheet type(s), " + f.derived.size() + " calculated value(s)", px, dy, MUTED, false);
			dy += 14;
		}

		int by = y + h - 56;
		g.drawText(textRenderer, "ADD A GAME", px, by, GOLD, false);
		String help = "Put a game folder (with a format.json inside) in the packs folder, then press Reload. Characters go in the characters folder.";
		int hy = by + 11;
		for (OrderedText line : textRenderer.wrapLines(StringVisitable.plain(help), pw)) {
			g.drawText(textRenderer, line, px, hy, MUTED, false);
			hy += 9;
		}
		int bx = px;
		bx += smallButton(g, bx, hy + 3, "Open packs folder", () -> Util.getOperatingSystem().open(SheetLibrary.packsDir().toFile()),
				SheetLibrary.packsDir().toString(), PANEL, 0xFF3B414C) + 4;
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
		editKind = sc.character.kind;
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

	private void chooseFormat(SheetFormat format, String kind) {
		editFormat = format;
		editKind = kind;
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
		c.kind = editKind;
		if (editChar != null) {
			// editing must not lose the layout choices made on the sheet itself
			c.theme = editChar.theme;
			c.hidden.addAll(editChar.hidden);
			c.order.putAll(editChar.order);
			// values the form does not offer (trackers such as death saves) stay as they were
			for (Map.Entry<String, Double> e : editChar.values.entrySet()) c.values.putIfAbsent(e.getKey(), e.getValue());
		}
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
		rowBoxes.clear();
		if (view == View.ROW && textRenderer != null) {
			buildRowForm();
			return;
		}
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
		g.drawText(textRenderer, "Format: " + tr(editFormat.name) + (editFormat.layouts.size() > 1 ? "  (" + tr(editFormat.layout(editKind).name) + ")" : ""), x + 8, y + 5, GOLD, false);
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
		int ly = y + 20;
		for (SheetFormat f : SheetLibrary.FORMATS.values()) {
			for (SheetFormat.Layout lay : f.layouts.values()) {
				boolean over = hit(x + 6, ly, w - 12, 34, () -> chooseFormat(f, lay.kind), null);
				g.fill(x + 6, ly, x + w - 6, ly + 34, EDGE);
				g.fill(x + 7, ly + 1, x + w - 7, ly + 33, over ? PANEL_HOVER : PANEL);
				g.drawText(textRenderer, tr(f.name) + (f.layouts.size() > 1 ? "  -  " + tr(lay.name) : ""), x + 12, ly + 4, 0xFFE8E6E1, false);
				var ms = g.getMatrices();
				ms.push();
				ms.translate(x + 12, ly + 16, 0);
				ms.scale(0.75f, 0.75f, 1.0f);
				int dy = 0;
				for (OrderedText line : textRenderer.wrapLines(StringVisitable.plain(tr(f.description)), (int) ((w - 30) / 0.75f))) {
					if (dy > 18) break;
					g.drawText(textRenderer, line, 0, dy, MUTED, false);
					dy += 9;
				}
				ms.pop();
				ly += 38;
				if (ly > y + h - 30) break;
			}
		}
		smallButton(g, x + 8, y + h - 18, "Cancel", this::cancelEdit, null, PANEL, 0xFF3B414C);
	}

	// ------------------------------------------------------------------ rolling

	private void rollDice(SheetContext sc, String label, String formula) {
		rollPlan(sc, label, () -> sc.plan(formula));
	}

	private void rollPlan(SheetContext sc, String label, java.util.function.Supplier<Expr.Roll> planner) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		try {
			Expr.Roll roll = planner.get();
			if (!ClientPlayNetworking.canSend(DiceRequestPayload.ID)) {
				mc.player.sendMessage(Text.literal("[sheet] This server does not have Tactical Combat, so it can not roll dice.")
						.formatted(Formatting.RED), false);
				return;
			}
			int mode = roll.type() == DiceType.D20 && roll.count() == 1 && hasRollMode(sc) ? rollMode : 0;
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
		if (button == 1) {
			// right click: only controls that define a second action (table numbers and choices) react
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				if (hit.contains(mx, my)) {
					if (hit.alt != null) {
						hit.alt.run();
						return true;
					}
					break;
				}
			}
		}
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
			List<SheetFormat.Page> visible = pages(sc);
			if (visible.isEmpty()) return super.mouseScrolled(mx, my, horizontal, vertical);
			SheetFormat.Page page = visible.get(MathHelper.clamp(pageIndex, 0, visible.size() - 1));
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
		boolean typing = (view == View.EDITOR || view == View.ROW) && getFocused() instanceof TextFieldWidget field && field.isFocused();
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && view == View.ROW) {
			cancelRow();
			return true;
		}
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
