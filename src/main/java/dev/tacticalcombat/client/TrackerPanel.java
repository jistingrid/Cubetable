package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.EncounterActionPayload;
import dev.tacticalcombat.sheet.SheetLibrary;
import dev.tacticalcombat.sheet.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * The Dungeon Master's combat tracker: a compact panel that stays on the screen for the whole fight. Everyone in
 * turn order with their hit points; hover a name to outline the model in the world, click it to open the sheet.
 * Drag the title bar to move it, the corner to resize it, {@code _} to fold it down to the title bar. It is drawn
 * and clicked through the combat screen, so the battlefield stays usable around it.
 */
public final class TrackerPanel {
	private static final int TITLE_H = 14;
	private static final int ROW_H = 18;
	private static final int FOOT_H = 16;
	private static final int MIN_W = 130;
	private static final int MIN_ROWS = 2;

	// position and size are kept for the whole session
	private static int x = Integer.MIN_VALUE;
	private static int y = 6;
	private static int w = 176;
	private static int h = -1; // -1: sized to fit the first time
	private static boolean collapsed;
	private static boolean hidden;
	private static int scroll;
	private static boolean endArmed;

	private static int dragMode; // 0 none, 1 move, 2 resize
	private static int glowing = -1;
	private static boolean glowWas;

	private TrackerPanel() {}

	/** The panel is for the Dungeon Master once the turns run. */
	public static boolean visible() {
		return ClientCombatState.active && !ClientCombatState.planning && ServerCharacters.isDm() && !hidden;
	}

	/** J (or /encounter) while the turns run: show or hide the tracker. */
	public static void toggleHidden() {
		hidden = !hidden;
		if (hidden) clearHighlight();
	}

	/** A new fight: the tracker shows again, folded out. */
	public static void reset() {
		hidden = false;
		collapsed = false;
		endArmed = false;
		scroll = 0;
		clearHighlight();
	}

	private record Geo(int x, int y, int w, int h) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static Geo geo(int screenW, int screenH, int rowsWanted) {
		int pw = MathHelper.clamp(w, MIN_W, Math.max(MIN_W, screenW - 8));
		int fullH = h < 0 ? TITLE_H + Math.max(MIN_ROWS, Math.min(rowsWanted, 8)) * ROW_H + FOOT_H : h;
		int ph = collapsed ? TITLE_H : MathHelper.clamp(fullH, TITLE_H + MIN_ROWS * ROW_H + FOOT_H, Math.max(TITLE_H + MIN_ROWS * ROW_H + FOOT_H, screenH - 8));
		if (x == Integer.MIN_VALUE) x = screenW - pw - 6;
		x = MathHelper.clamp(x, 2, Math.max(2, screenW - pw - 2));
		y = MathHelper.clamp(y, 2, Math.max(2, screenH - ph - 2));
		return new Geo(x, y, pw, ph);
	}

	public static boolean contains(double mx, double my, int screenW, int screenH) {
		return visible() && geo(screenW, screenH, ClientCombatState.entries.size()).contains(mx, my);
	}

	// ------------------------------------------------------------------ drawing

	/** Draws the panel; returns the tooltip for whatever the mouse is over (or null). */
	public static String render(DrawContext g, TextRenderer font, int mx, int my, int screenW, int screenH) {
		if (!visible()) {
			clearHighlight();
			return null;
		}
		List<CombatStatePayload.Entry> entries = ClientCombatState.entries;
		Geo geo = geo(screenW, screenH, entries.size());
		Theme t = SheetLibrary.theme("");
		int bg = t.get("bg"), panel = t.get("panel"), hover = t.get("panel_hover"), edge = t.get("edge");
		int gold = t.get("accent"), dim = t.get("dim");

		g.fill(geo.x - 1, geo.y - 1, geo.x + geo.w + 1, geo.y + geo.h + 1, 0xFF05060A);
		g.fill(geo.x, geo.y, geo.x + geo.w, geo.y + geo.h, bg);

		String tip = null;

		// title bar
		g.fill(geo.x, geo.y, geo.x + geo.w, geo.y + TITLE_H, 0xFF0F1114);
		g.fill(geo.x, geo.y + TITLE_H - 1, geo.x + geo.w, geo.y + TITLE_H, edge);
		int bx = geo.x + geo.w - 3;
		bx -= small(g, font, bx - 11, geo.y + 2, "x", mx, my, 0xFF7A1C27) + 2;
		bx -= small(g, font, bx - 11, geo.y + 2, collapsed ? "+" : "_", mx, my, panel) + 2;
		String title;
		if (collapsed && !entries.isEmpty()) {
			int i = Math.min(Math.max(0, ClientCombatState.activeIndex), entries.size() - 1);
			title = "R" + ClientCombatState.round + "  " + displayName(entries.get(i), false);
		} else {
			title = "Combat - round " + ClientCombatState.round;
		}
		g.drawText(font, font.trimToWidth(title, Math.max(10, bx - geo.x - 10)), geo.x + 5, geo.y + 3, 0xFFC9CCD2, false);
		if (over(mx, my, bx + 2, geo.y + 2, geo.x + geo.w - 3 - (bx + 2), 10)) tip = collapsed ? "Fold out (+) or hide (x)" : "Fold up (_) or hide (x)  -  J brings it back";

		if (collapsed) {
			setHighlight(-1);
			return tip;
		}

		// rows
		int listY = geo.y + TITLE_H;
		int listH = geo.h - TITLE_H - FOOT_H;
		int rows = Math.max(1, listH / ROW_H);
		int maxScroll = Math.max(0, entries.size() - rows);
		scroll = MathHelper.clamp(scroll, 0, maxScroll);
		int hoverId = -1;
		g.enableScissor(geo.x, listY, geo.x + geo.w, listY + listH);
		for (int i = 0; i < rows + 1 && i + scroll < entries.size(); i++) {
			int index = i + scroll;
			CombatStatePayload.Entry e = entries.get(index);
			int ry = listY + i * ROW_H;
			boolean current = index == ClientCombatState.activeIndex;
			boolean over = geo.contains(mx, my) && mx >= geo.x && mx < geo.x + geo.w && my >= ry && my < ry + ROW_H && my < listY + listH;
			if (over) {
				hoverId = e.entityId();
				tip = displayName(e, true) + (CharacterSheetScreen.find(e.sheetId(), e.playerName()) != null ? "  -  click: open the sheet" : "  -  no sheet");
			}
			g.fill(geo.x, ry, geo.x + geo.w, ry + ROW_H, over ? hover : current ? panel : bg);
			g.fill(geo.x, ry, geo.x + 2, ry + ROW_H, e.hostile() ? 0xFFC83232 : 0xFF3CB84A);
			if (current) g.fill(geo.x, ry + ROW_H - 1, geo.x + geo.w, ry + ROW_H, gold);
			g.drawItem(CombatHud.iconFor(e), geo.x + 4, ry + 1);

			String hp = Math.round(e.health()) + "/" + Math.round(e.maxHealth());
			int hpW = font.getWidth(hp);
			int hx = geo.x + geo.w - 5 - hpW;
			g.drawText(font, hp, hx, ry + 3, current ? gold : 0xFFE6E8EB, false);
			int nameX = geo.x + 24;
			g.drawText(font, font.trimToWidth(displayName(e, false), Math.max(10, hx - nameX - 4)), nameX, ry + 3, current ? gold : 0xFFE6E8EB, false);
			float frac = e.maxHealth() <= 0 ? 0 : Math.max(0f, Math.min(1f, e.health() / e.maxHealth()));
			int barW = geo.w - 30;
			g.fill(nameX, ry + 13, nameX + barW, ry + 15, 0xFF05060A);
			g.fill(nameX, ry + 13, nameX + Math.round(barW * frac), ry + 15, e.hostile() ? 0xFFE04040 : 0xFF50D060);
		}
		g.disableScissor();
		setHighlight(hoverId);
		if (maxScroll > 0) g.drawText(font, "...", geo.x + geo.w - 14, listY + listH - 9, dim, false);

		// footer: end the turn / end the fight
		int fy = geo.y + geo.h - FOOT_H;
		g.fill(geo.x, fy, geo.x + geo.w, fy + 1, edge);
		int half = geo.w / 2;
		boolean overTurn = over(mx, my, geo.x, fy + 1, half, FOOT_H - 1);
		boolean overEnd = over(mx, my, geo.x + half, fy + 1, geo.w - half, FOOT_H - 1);
		g.fill(geo.x, fy + 1, geo.x + half - 1, geo.y + geo.h, overTurn ? hover : panel);
		g.fill(geo.x + half, fy + 1, geo.x + geo.w, geo.y + geo.h, overEnd ? (endArmed ? 0xFFB8323F : hover) : endArmed ? 0xFF7A1C27 : panel);
		String a = "End turn", b = endArmed ? "Really end?" : "End combat";
		g.drawText(font, a, geo.x + (half - font.getWidth(a)) / 2, fy + 5, 0xFFFFFFFF, false);
		g.drawText(font, b, geo.x + half + (geo.w - half - font.getWidth(b)) / 2, fy + 5, 0xFFFFFFFF, false);
		if (overTurn) tip = "Finish the current turn and start the next one";
		if (overEnd) tip = "Stop the fight for everyone (click twice)";
		if (!overEnd) endArmed = endArmed && geo.contains(mx, my);

		// resize grip
		boolean grip = over(mx, my, geo.x + geo.w - 8, geo.y + geo.h - 8, 8, 8) || dragMode == 2;
		for (int i = 0; i < 3; i++) {
			g.fill(geo.x + geo.w - 3 - i * 2, geo.y + geo.h - 2, geo.x + geo.w - 2 - i * 2, geo.y + geo.h - 1, grip ? gold : dim);
		}
		if (grip && tip == null) tip = "Drag to resize";
		return tip;
	}

	private static boolean over(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private static int small(DrawContext g, TextRenderer font, int bx, int by, String text, int mx, int my, int fill) {
		boolean over = over(mx, my, bx, by, 11, 10);
		g.fill(bx, by, bx + 11, by + 10, 0xFF3B414C);
		g.fill(bx + 1, by + 1, bx + 10, by + 9, over ? 0xFF2B313A : fill);
		g.drawText(font, text, bx + 6 - font.getWidth(text) / 2, by + 1, 0xFFFFFFFF, false);
		return 11;
	}

	/** A player entry shows its character's name when the sheet is known (full = with the player's name after it). */
	private static String displayName(CombatStatePayload.Entry e, boolean full) {
		String base = CombatHud.nameOf(e).getString();
		if (e.playerName().isEmpty()) return base;
		var c = CharacterSheetScreen.find(e.sheetId(), e.playerName());
		if (c == null) return base;
		return full ? c.displayName() + " (" + e.playerName() + ")" : c.displayName();
	}

	// ------------------------------------------------------------------ input (called by the combat screen)

	public static boolean mouseClicked(double mx, double my, int button, int screenW, int screenH) {
		if (!visible()) return false;
		List<CombatStatePayload.Entry> entries = ClientCombatState.entries;
		Geo geo = geo(screenW, screenH, entries.size());
		if (!geo.contains(mx, my)) return false;
		if (button != 0) return true;

		if (my < geo.y + TITLE_H) {
			int right = geo.x + geo.w - 3;
			if (over((int) mx, (int) my, right - 11, geo.y + 2, 11, 10)) {
				hidden = true;
				clearHighlight();
			} else if (over((int) mx, (int) my, right - 24, geo.y + 2, 11, 10)) {
				collapsed = !collapsed;
			} else {
				dragMode = 1;
			}
			return true;
		}
		if (collapsed) return true;

		if (over((int) mx, (int) my, geo.x + geo.w - 8, geo.y + geo.h - 8, 8, 8)) {
			dragMode = 2;
			return true;
		}
		int fy = geo.y + geo.h - FOOT_H;
		if (my >= fy) {
			if (mx < geo.x + geo.w / 2) {
				ClientPlayNetworking.send(new EncounterActionPayload(6, 0, 0));
			} else if (endArmed) {
				endArmed = false;
				ClientPlayNetworking.send(new EncounterActionPayload(7, 0, 0));
			} else {
				endArmed = true;
			}
			return true;
		}
		int listY = geo.y + TITLE_H;
		int index = scroll + (int) ((my - listY) / ROW_H);
		if (index >= 0 && index < entries.size()) {
			CombatStatePayload.Entry e = entries.get(index);
			if (CharacterSheetScreen.find(e.sheetId(), e.playerName()) != null) {
				clearHighlight();
				CharacterSheetScreen.open(MinecraftClient.getInstance(), null, e.sheetId(), e.playerName());
			}
		}
		return true;
	}

	public static boolean mouseDragged(double dx, double dy) {
		if (dragMode == 1) {
			x += (int) Math.round(dx);
			y += (int) Math.round(dy);
			return true;
		}
		if (dragMode == 2) {
			w += (int) Math.round(dx);
			int base = h < 0 ? TITLE_H + Math.max(MIN_ROWS, Math.min(8, ClientCombatState.entries.size())) * ROW_H + FOOT_H : h;
			h = base + (int) Math.round(dy);
			return true;
		}
		return false;
	}

	public static boolean mouseReleased() {
		boolean was = dragMode != 0;
		dragMode = 0;
		return was;
	}

	public static boolean mouseScrolled(double mx, double my, double vertical, int screenW, int screenH) {
		if (!contains(mx, my, screenW, screenH) || collapsed) return false;
		scroll = Math.max(0, scroll - (int) Math.signum(vertical));
		return true;
	}

	// ------------------------------------------------------------------ outlining the hovered model

	private static void setHighlight(int id) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
		if (id != glowing) {
			clearHighlight();
			Entity e = id < 0 ? null : mc.world.getEntityById(id);
			if (e != null) {
				glowing = id;
				glowWas = e.isGlowing();
			}
		}
		if (glowing >= 0) {
			Entity e = mc.world.getEntityById(glowing);
			if (e != null) e.setGlowing(true); // the server may reset the flag, so it is set again every frame
		}
	}

	/** Removes the outline from the model that was being pointed at. */
	public static void clearHighlight() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (glowing >= 0 && mc.world != null) {
			Entity e = mc.world.getEntityById(glowing);
			if (e != null) e.setGlowing(glowWas);
		}
		glowing = -1;
	}
}
