package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.EncounterActionPayload;
import dev.tacticalcombat.sheet.SheetLibrary;
import dev.tacticalcombat.sheet.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The encounter manager: opens when a fight starts. Players on the left, enemies on the right, each with their
 * initiative. A player rolls their own; the Dungeon Master rolls the enemies, can adjust any value, reorder
 * the turn order and start the turns. A game whose pack has no initiative roll shows no roll buttons, and the
 * DM simply orders the turns by hand.
 */
public final class EncounterScreen extends Screen {
	private static final int W = 400;
	private static final int H = 262;
	private static final int TITLE_H = 16;
	private static final int ROW_H = 24;
	private static final int STRIP_H = 56;
	private static final int FOOT_H = 24;

	private static boolean pendingOpen;
	private static boolean pendingAuto;

	private final int bg, panel, panelHover, edge, gold, muted, dim, bannerA, bannerB;

	private record Hit(int x, int y, int w, int h, Runnable action, String tip) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private int mouseX, mouseY;
	private int winX = Integer.MIN_VALUE, winY;
	private boolean dragging;
	private int scroll;
	/** Opened by the start of a fight: closes by itself when the turns begin. */
	private final boolean autoClose;

	public EncounterScreen(boolean autoClose) {
		super(Text.empty());
		this.autoClose = autoClose;
		Theme t = SheetLibrary.theme("");
		bg = t.get("bg");
		panel = t.get("panel");
		panelHover = t.get("panel_hover");
		edge = t.get("edge");
		gold = t.get("accent");
		muted = t.get("muted");
		dim = t.get("dim");
		bannerA = t.get("banner_a");
		bannerB = t.get("banner_b");
	}

	/** From the /encounter command. */
	public static void requestOpen() {
		pendingOpen = true;
	}

	/** Called every client tick: opens the window when a fight starts or the command asked for it. */
	public static void tick(MinecraftClient client) {
		if (ClientCombatState.openEncounterPending) {
			ClientCombatState.openEncounterPending = false;
			pendingOpen = true;
			pendingAuto = true;
		}
		if (!pendingOpen || client.player == null) return;
		if (!ClientCombatState.active) {
			pendingOpen = false;
			pendingAuto = false;
			return;
		}
		if (client.currentScreen == null || client.currentScreen instanceof TacticalScreen) {
			client.setScreen(new EncounterScreen(pendingAuto));
			pendingOpen = false;
			pendingAuto = false;
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// the battlefield stays visible behind the window
	}

	@Override
	public void tick() {
		if (!ClientCombatState.active || autoClose && !ClientCombatState.planning) close();
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(null); // the combat screen comes back by itself
	}

	private int tw(String s) {
		return textRenderer.getWidth(s);
	}

	private boolean hit(int x, int y, int w, int h, Runnable action, String tip) {
		hits.add(new Hit(x, y, w, h, action, tip));
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private int button(DrawContext g, int x, int y, String text, Runnable action, String tip, int fill, int edgeColor) {
		int bw = Math.max(12, tw(text) + 8);
		boolean over = hit(x, y, bw, 12, action, tip);
		g.fill(x, y, x + bw, y + 12, edgeColor);
		g.fill(x + 1, y + 1, x + bw - 1, y + 11, over ? panelHover : fill);
		g.drawText(textRenderer, text, x + (bw - tw(text)) / 2, y + 2, 0xFFFFFFFF, false);
		return bw;
	}

	private static void send(int op, int entityId, int value) {
		ClientPlayNetworking.send(new EncounterActionPayload(op, entityId, value));
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(DrawContext g, int mx, int my, float delta) {
		mouseX = mx;
		mouseY = my;
		hits.clear();

		int w = Math.min(W, width - 8);
		int h = Math.min(H, height - 8);
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
		g.fill(x, y, x + w, y + h, bg);

		boolean dm = ServerCharacters.isDm();
		boolean planning = ClientCombatState.planning;
		List<CombatStatePayload.Entry> entries = ClientCombatState.entries;

		// title bar
		g.fill(x, y, x + w, y + TITLE_H, 0xFF0F1114);
		g.fill(x, y + TITLE_H - 1, x + w, y + TITLE_H, edge);
		String title = planning ? "Encounter - initiative" : "Encounter - round " + ClientCombatState.round;
		g.drawText(textRenderer, title, x + 6, y + 4, 0xFFC9CCD2, false);
		int right = x + w - 3;
		right -= button(g, right - 12, y + 3, "x", this::close, "Close (the fight carries on; /encounter opens this again)", 0xFF7A1C27, 0xFFB8323F) + 3;
		if (dm) {
			g.fill(right - tw("DM") - 8, y + 3, right, y + 14, 0xFF6B4E12);
			g.drawText(textRenderer, "DM", right - tw("DM") - 4, y + 5, gold, false);
		}

		// column geometry
		int pad = 8;
		int colW = (w - pad * 3) / 2;
		int leftX = x + pad;
		int rightX = x + pad * 2 + colW;
		int headY = y + TITLE_H + 6;
		int listY = headY + 16;
		int listH = h - TITLE_H - 6 - 16 - STRIP_H - FOOT_H - 4;

		List<CombatStatePayload.Entry> party = new ArrayList<>();
		List<CombatStatePayload.Entry> foes = new ArrayList<>();
		for (CombatStatePayload.Entry e : entries) (e.hostile() ? foes : party).add(e);

		// column headers
		drawHeader(g, leftX, headY, colW, "Party (" + party.size() + ")");
		drawHeader(g, rightX, headY, colW, "Enemies (" + foes.size() + ")");
		boolean canRollFoes = false;
		for (CombatStatePayload.Entry e : foes) if (e.rollable() && !e.rolled()) canRollFoes = true;
		if (dm && planning && canRollFoes) {
			String label = "Roll enemies";
			button(g, rightX + colW - tw(label) - 10, headY + 2, label, () -> send(1, 0, 0), "Roll initiative for every enemy", 0xFF1E4A25, 0xFF3CB84A);
		}

		int rows = Math.max(1, listH / ROW_H);
		int maxScroll = Math.max(0, Math.max(party.size(), foes.size()) - rows);
		scroll = MathHelper.clamp(scroll, 0, maxScroll);
		g.enableScissor(x, listY, x + w, listY + listH);
		drawColumn(g, party, leftX, listY, colW, rows, dm, planning);
		drawColumn(g, foes, rightX, listY, colW, rows, dm, planning);
		g.disableScissor();
		if (maxScroll > 0) {
			g.drawText(textRenderer, "scroll for more", x + w / 2 - tw("scroll for more") / 2, listY + listH - 8, dim, false);
		}

		// turn order strip
		int stripY = y + h - FOOT_H - STRIP_H;
		g.fill(x + 1, stripY, x + w - 1, stripY + 1, edge);
		g.drawText(textRenderer, "Turn order", x + pad, stripY + 4, muted, false);
		drawOrder(g, entries, x + pad, stripY + 15, w - pad * 2, dm && planning);

		// footer
		int footY = y + h - FOOT_H;
		g.fill(x + 1, footY, x + w - 1, footY + 1, edge);
		if (planning) {
			if (dm) {
				String start = "Start combat";
				int bw = tw(start) + 14;
				button(g, x + w - bw - 8, footY + 6, start, () -> send(4, 0, 0), "Begin the first turn", 0xFF6B4E12, gold);
				int sw = button(g, x + pad, footY + 6, "Sort by initiative", () -> send(5, 0, 0), "Put everyone in the order of their results again", panel, 0xFF3B414C);
				g.drawText(textRenderer, "Move with < >, then start.", x + pad + sw + 8, footY + 8, dim, false);
			} else {
				g.drawText(textRenderer, "Roll your initiative, then wait for the Dungeon Master.", x + pad, footY + 8, dim, false);
			}
		} else {
			g.drawText(textRenderer, "The fight is under way.", x + pad, footY + 8, dim, false);
		}

		String tooltip = null;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tip != null && hit.contains(mx, my)) {
				tooltip = hit.tip;
				break;
			}
		}
		if (tooltip != null) g.drawTooltip(textRenderer, Text.literal(tooltip), mx, my);
	}

	private void drawHeader(DrawContext g, int x, int y, int w, String text) {
		g.fill(x, y, x + w, y + 14, bannerA);
		g.fill(x, y + 12, x + w, y + 14, bannerB);
		g.drawText(textRenderer, text, x + 5, y + 3, 0xFFFFFFFF, false);
	}

	private void drawColumn(DrawContext g, List<CombatStatePayload.Entry> list, int x, int y, int w, int rows, boolean dm, boolean planning) {
		if (list.isEmpty()) {
			g.drawText(textRenderer, "Nobody", x + 5, y + 6, dim, false);
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		int myId = mc.player == null ? -1 : mc.player.getId();
		for (int i = 0; i < rows + 1 && i + scroll < list.size(); i++) {
			CombatStatePayload.Entry e = list.get(i + scroll);
			int ry = y + i * ROW_H;
			g.fill(x, ry, x + w, ry + ROW_H - 2, panel);
			g.fill(x, ry, x + 2, ry + ROW_H - 2, e.hostile() ? 0xFFC83232 : 0xFF3CB84A);
			g.drawItem(CombatHud.iconFor(e), x + 5, ry + 3);

			// right side, from the edge inwards: DM +/- around the value, then a roll button
			int rx = x + w - 4;
			if (dm && planning) {
				rx -= button(g, rx - 12, ry + 5, "+", () -> send(2, e.entityId(), e.initiative() + step()), "+1 (Shift +5)", panel, 0xFF3B414C);
				rx -= 2;
				String val = e.rolled() ? String.valueOf(e.initiative()) : "-";
				rx -= 24;
				g.drawCenteredTextWithShadow(textRenderer, val, rx + 12, ry + 7, gold);
				rx -= 2;
				rx -= button(g, rx - 12, ry + 5, "-", () -> send(2, e.entityId(), e.initiative() - step()), "-1 (Shift -5)", panel, 0xFF3B414C);
			} else {
				String val = e.rolled() ? String.valueOf(e.initiative()) : "-";
				rx -= 24;
				g.drawCenteredTextWithShadow(textRenderer, val, rx + 12, ry + 7, e.rolled() ? gold : dim);
			}
			boolean mine = e.entityId() == myId;
			boolean canRoll = planning && e.rollable() && !e.rolled() && mine;
			if (canRoll) {
				rx -= 4;
				rx -= button(g, rx - tw("Roll") - 8, ry + 5, "Roll", () -> send(0, 0, 0), "Roll your initiative", 0xFF1E4A25, 0xFF3CB84A);
			}

			String name = CombatHud.nameOf(e).getString();
			g.drawText(textRenderer, textRenderer.trimToWidth(name, Math.max(20, rx - (x + 24) - 4)), x + 24, ry + 3, 0xFFE6E8EB, false);
			float frac = e.maxHealth() <= 0 ? 0 : Math.max(0f, Math.min(1f, e.health() / e.maxHealth()));
			int barW = Math.max(20, Math.min(70, rx - (x + 24) - 6));
			g.fill(x + 24, ry + 14, x + 24 + barW, ry + 17, 0xFF05060A);
			g.fill(x + 24, ry + 14, x + 24 + Math.round(barW * frac), ry + 17, e.hostile() ? 0xFFE04040 : 0xFF50D060);
		}
	}

	private void drawOrder(DrawContext g, List<CombatStatePayload.Entry> entries, int x, int y, int w, boolean edit) {
		int n = entries.size();
		if (n == 0) return;
		int gap = 3;
		int chipW = Math.max(30, Math.min(70, (w - gap * (n - 1)) / n));
		int visible = Math.max(1, (w + gap) / (chipW + gap));
		for (int i = 0; i < n && i < visible; i++) {
			CombatStatePayload.Entry e = entries.get(i);
			int cx = x + i * (chipW + gap);
			boolean current = !ClientCombatState.planning && i == ClientCombatState.activeIndex;
			g.fill(cx, y, cx + chipW, y + 24, current ? gold : e.hostile() ? 0xFF7A2A2A : 0xFF2A6B33);
			g.fill(cx + 1, y + 1, cx + chipW - 1, y + 23, panel);
			g.drawText(textRenderer, (i + 1) + ". " + (e.rolled() ? e.initiative() : "-"), cx + 3, y + 3, gold, false);
			g.drawText(textRenderer, textRenderer.trimToWidth(CombatHud.nameOf(e).getString(), chipW - 6), cx + 3, y + 13, 0xFFE6E8EB, false);
			if (edit) {
				int id = e.entityId();
				if (i > 0) button(g, cx, y + 26, "<", () -> send(3, id, -1), "Earlier in the order", panel, 0xFF3B414C);
				if (i < n - 1) button(g, cx + chipW - 12, y + 26, ">", () -> send(3, id, 1), "Later in the order", panel, 0xFF3B414C);
			}
		}
		if (n > visible) g.drawText(textRenderer, "+" + (n - visible) + " more", x + w - 40, y - 11, dim, false);
	}

	private int step() {
		return hasShiftDown() ? 5 : 1;
	}

	// ------------------------------------------------------------------ input

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
			int w = Math.min(W, width - 8);
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
		scroll = Math.max(0, scroll - (int) Math.signum(vertical));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
