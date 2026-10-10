package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.DmActionPayload;
import dev.tacticalcombat.net.DmStatePayload;
import dev.tacticalcombat.net.EncounterActionPayload;
import dev.tacticalcombat.sheet.SheetLibrary;
import dev.tacticalcombat.sheet.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The encounter manager and, for a Dungeon Master, the DM screen. Players get it for the initiative phase of a
 * fight. A Dungeon Master can open it at any time (J or /encounter): the Encounter tab lists the party (the players
 * online when no fight runs, with a Start encounter button; the fighters otherwise, with End encounter) and the
 * DM tools tab holds switches such as Auto movement.
 *
 * <p>The encounter manager: opens when a fight starts, for the initiative phase. Once the turns run it closes; the
 * Dungeon Master then has the compact {@link TrackerPanel} on the screen. Players on the left, enemies on the right, each with their
 * initiative. A player rolls their own; the Dungeon Master rolls the enemies, can adjust any value, reorder
 * the turn order and start the turns. A game whose pack has no initiative roll shows no roll buttons, and the
 * DM simply orders the turns by hand.
 */
public final class EncounterScreen extends Screen {
	/** Remembered size of the window for each tab: Encounter, DM tools, Actors. */
	private static final int[][] SIZE = {{400, 262}, {250, 190}, {380, 236}};
	private static final int[][] MIN = {{300, 210}, {200, 110}, {290, 170}};
	private static final String[] TAB_NAMES = {"Encounter", "DM tools", "Actors"};
	private static final int GRAB = 5;
	private static final int TITLE_H = 16;
	private static final int ROW_H = 24;
	private static final int STRIP_H = 56;
	private static final int FOOT_H = 24;

	private static final int TAB_H = 14;
	private static final int W_FALLBACK = 250;
	private static final int H_FALLBACK = 190;

	private static boolean pendingOpen;
	private static boolean pendingAuto;
	/** A Dungeon Master asked for the screen. */
	private static boolean pendingDm;
	/** Remembered between openings: 0 the encounter, 1 the DM tools, 2 the Actors. */
	private static int tab;
	/** A tab somebody asked for (the /actors command, the Stage's Actors button). */
	private static int pendingTab = -1;
	private boolean endArmed;
	private long endArmedAt;

	final int bg, panel, panelHover, edge, gold, muted, dim, bannerA, bannerB;

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
	private int toolScroll;
	private int toolHeight;
	private int toolArea;
	private int curW = W_FALLBACK, curH = H_FALLBACK;
	/** 0 none, 1 right edge, 2 bottom edge, 3 corner. */
	private int resizeMode;
	private int resizeMouseX, resizeMouseY, resizeW, resizeH;
	private final ActorsPanel actors = new ActorsPanel();
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

	/**
	 * From the J key or /encounter. A Dungeon Master gets the screen whenever they ask (and J closes it again); a
	 * player gets the initiative window while the fight is being planned.
	 */
	public static void requestOpen() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (ServerCharacters.isDm()) {
			if (mc.currentScreen instanceof EncounterScreen es) es.close();
			else pendingDm = true;
			return;
		}
		if (ClientCombatState.active && !ClientCombatState.planning) return; // nothing to open for players while the turns run
		pendingOpen = true;
	}

	/** Opens the DM window on a tab (/actors, the Stage's Actors button). */
	public static void requestTab(int index) {
		if (!ServerCharacters.isDm()) {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player != null) mc.player.sendMessage(Text.literal("Only a Dungeon Master has the DM window."), false);
			return;
		}
		pendingTab = index;
		pendingDm = true;
	}

	/** Called every client tick: opens the window when a fight starts or somebody asked for it. */
	public static void tick(MinecraftClient client) {
		if (ClientCombatState.openEncounterPending) {
			ClientCombatState.openEncounterPending = false;
			TrackerPanel.reset();
			pendingOpen = true;
			pendingAuto = true;
		}
		if (client.player == null) return;
		boolean free = client.currentScreen == null || client.currentScreen instanceof TacticalScreen;
		if (pendingDm) {
			if (free || client.currentScreen instanceof EncounterScreen) {
				if (pendingTab >= 0) tab = Math.min(pendingTab, TAB_NAMES.length - 1);
				pendingTab = -1;
				if (!(client.currentScreen instanceof EncounterScreen)) client.setScreen(new EncounterScreen(false));
				pendingDm = false;
				pendingOpen = false;
				pendingAuto = false;
			}
			return;
		}
		if (!pendingOpen) return;
		if (!ClientCombatState.active || !ClientCombatState.planning) { // nothing to open here any more
			pendingOpen = false;
			pendingAuto = false;
			return;
		}
		if (free) {
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
		// opened by the start of a fight (or by a player): it goes when the turns begin. A Dungeon Master who opened it stays.
		if ((autoClose || !ServerCharacters.isDm()) && (!ClientCombatState.active || !ClientCombatState.planning)) close();
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(null); // the combat screen comes back by itself
	}

	int tw(String s) {
		return textRenderer.getWidth(s);
	}

	boolean hit(int x, int y, int w, int h, Runnable action, String tip) {
		hits.add(new Hit(x, y, w, h, action, tip));
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	int button(DrawContext g, int x, int y, String text, Runnable action, String tip, int fill, int edgeColor) {
		int bw = Math.max(12, tw(text) + 8);
		boolean over = hit(x, y, bw, 12, action, tip);
		g.fill(x, y, x + bw, y + 12, edgeColor);
		g.fill(x + 1, y + 1, x + bw - 1, y + 11, over ? panelHover : fill);
		g.drawText(textRenderer, text, x + (bw - tw(text)) / 2, y + 2, 0xFFFFFFFF, false);
		return bw;
	}

	net.minecraft.client.font.TextRenderer font() {
		return textRenderer;
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
		if (endArmed && System.currentTimeMillis() - endArmedAt > 3000) endArmed = false;

		boolean dm = ServerCharacters.isDm();
		if (!dm) tab = 0;
		int w = MathHelper.clamp(SIZE[tab][0], MIN[tab][0], Math.max(MIN[tab][0], width - 8));
		int h = MathHelper.clamp(SIZE[tab][1], MIN[tab][1], Math.max(MIN[tab][1], height - 8));
		curW = w;
		curH = h;
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

		boolean inFight = ClientCombatState.active;
		boolean planning = ClientCombatState.planning;
		List<CombatStatePayload.Entry> entries = ClientCombatState.entries;

		// title bar
		g.fill(x, y, x + w, y + TITLE_H, 0xFF0F1114);
		g.fill(x, y + TITLE_H - 1, x + w, y + TITLE_H, edge);
		String title = dm && tab == 1 ? "DM tools" : dm && tab == 2 ? "Actors" : !inFight ? "Encounter" : planning ? "Encounter - initiative" : "Combat - round " + ClientCombatState.round;
		g.drawText(textRenderer, title, x + 6, y + 4, 0xFFC9CCD2, false);
		int right = x + w - 3;
		right -= button(g, right - 12, y + 3, "x", this::close, "Close (the fight carries on; J or /encounter opens this again)", 0xFF7A1C27, 0xFFB8323F) + 3;
		if (dm) {
			g.fill(right - tw("DM") - 8, y + 3, right, y + 14, 0xFF6B4E12);
			g.drawText(textRenderer, "DM", right - tw("DM") - 4, y + 5, gold, false);
		}

		// tabs (Dungeon Master only)
		int top = y + TITLE_H;
		if (dm) {
			String[] names = TAB_NAMES;
			int tx = x + 6;
			for (int i = 0; i < names.length; i++) {
				final int index = i;
				int tabW = tw(names[i]) + 14;
				boolean over = hit(tx, top + 2, tabW, TAB_H - 2, () -> tab = index, null);
				g.fill(tx, top + 2, tx + tabW, top + TAB_H, tab == i ? gold : edge);
				g.fill(tx + 1, top + 3, tx + tabW - 1, top + TAB_H, tab == i ? panel : over ? panelHover : bg);
				g.drawText(textRenderer, names[i], tx + 7, top + 5, tab == i ? 0xFFFFFFFF : muted, false);
				tx += tabW + 3;
			}
			g.fill(x + 1, top + TAB_H, x + w - 1, top + TAB_H + 1, edge);
			top += TAB_H;
		}

		if (dm && tab == 1) {
			drawTools(g, x, top, w, h - (top - y));
			drawGrip(g, x, y, w, h);
			drawTooltip(g, mx, my);
			return;
		}
		if (dm && tab == 2) {
			actors.draw(this, g, x, top + 1, w, h - (top - y) - 1);
			drawGrip(g, x, y, w, h);
			drawTooltip(g, mx, my);
			return;
		}

		// column geometry
		int pad = 8;
		int colW = (w - pad * 3) / 2;
		int leftX = x + pad;
		int rightX = x + pad * 2 + colW;
		int headY = top + 6;
		int listY = headY + 16;
		int stripH = inFight ? STRIP_H : 0;
		int listH = h - (top - y) - 6 - 16 - stripH - FOOT_H - 4;
		int footY = y + h - FOOT_H;

		if (!inFight) { // a Dungeon Master looking at the table between fights
			drawHeader(g, leftX, headY, colW, "Party (" + DmState.players.size() + ")");
			drawHeader(g, rightX, headY, colW, "Enemies");
			g.enableScissor(x, listY, x + w, listY + listH);
			drawIdleParty(g, leftX, listY, colW, listH / ROW_H + 1);
			int ty = listY + 4;
			for (net.minecraft.text.OrderedText line : textRenderer.wrapLines(
					Text.literal("Hostile creatures near the party join when the encounter starts."), colW - 10)) {
				g.drawText(textRenderer, line, rightX + 5, ty, dim, false);
				ty += 10;
			}
			g.disableScissor();
			g.fill(x + 1, footY, x + w - 1, footY + 1, edge);
			String start = "Start encounter";
			button(g, x + w - tw(start) - 22, footY + 6, start, () -> ClientPlayNetworking.send(new DmActionPayload(0, 0)),
					"Gather the party and every hostile creature nearby", 0xFF6B4E12, gold);
			g.drawText(textRenderer, "No fight is running.", x + pad, footY + 8, dim, false);
			drawGrip(g, x, y, w, h);
			drawTooltip(g, mx, my);
			return;
		}

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
		g.fill(x + 1, footY, x + w - 1, footY + 1, edge);
		int fx = x + w - 8;
		if (dm) {
			String end = endArmed ? "Really end?" : "End encounter";
			int ew = tw(end) + 8;
			fx -= button(g, fx - ew, footY + 6, end, this::endEncounter, "Stop the fight (click twice)", 0xFF7A1C27, 0xFFB8323F);
			fx -= 6;
		}
		if (planning) {
			if (dm) {
				String start = "Start combat";
				fx -= button(g, fx - tw(start) - 8, footY + 6, start, () -> send(4, 0, 0), "Begin the first turn", 0xFF6B4E12, gold);
				button(g, x + pad, footY + 6, "Sort by initiative", () -> send(5, 0, 0), "Put everyone in the order of their results again", panel, 0xFF3B414C);
			} else {
				g.drawText(textRenderer, "Roll your initiative, then wait for the Dungeon Master.", x + pad, footY + 8, dim, false);
			}
		} else {
			g.drawText(textRenderer, "The fight is under way.", x + pad, footY + 8, dim, false);
		}

		drawGrip(g, x, y, w, h);
		drawTooltip(g, mx, my);
	}

	private void endEncounter() {
		if (!endArmed) {
			endArmed = true;
			endArmedAt = System.currentTimeMillis();
			return;
		}
		endArmed = false;
		send(7, 0, 0);
	}

	/** The players online, for the Dungeon Master between fights. */
	private void drawIdleParty(DrawContext g, int x, int y, int w, int rows) {
		List<DmStatePayload.Who> list = DmState.players;
		if (list.isEmpty()) {
			g.drawText(textRenderer, "Nobody", x + 5, y + 6, dim, false);
			return;
		}
		scroll = MathHelper.clamp(scroll, 0, Math.max(0, list.size() - rows));
		for (int i = 0; i < rows && i + scroll < list.size(); i++) {
			DmStatePayload.Who p = list.get(i + scroll);
			int ry = y + i * ROW_H;
			g.fill(x, ry, x + w, ry + ROW_H - 2, panel);
			g.fill(x, ry, x + 2, ry + ROW_H - 2, 0xFF3CB84A);
			g.drawItem(new ItemStack(Items.PLAYER_HEAD), x + 5, ry + 3);
			g.drawText(textRenderer, textRenderer.trimToWidth(p.name(), w - 30), x + 24, ry + 3, 0xFFE6E8EB, false);
			float frac = p.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, p.hp() / p.max()));
			int barW = Math.min(70, w - 30);
			g.fill(x + 24, ry + 14, x + 24 + barW, ry + 17, 0xFF05060A);
			g.fill(x + 24, ry + 14, x + 24 + Math.round(barW * frac), ry + 17, 0xFF50D060);
			String hp = Math.round(p.hp()) + "/" + Math.round(p.max());
			g.drawText(textRenderer, hp, x + 24 + barW + 4, ry + 12, muted, false);
		}
	}

	/** The DM tools tab: one compact row per tool (name, a ? that shows what it does, and its switch). Scrolls. */
	private void drawTools(DrawContext g, int x, int top, int w, int h) {
		int pad = 6;
		int areaTop = top + 4;
		int areaH = h - 8;
		toolArea = areaH;
		int ry = areaTop - toolScroll;
		int start = ry;
		g.enableScissor(x + 1, areaTop, x + w - 1, areaTop + areaH);
		ry += tool(g, x + pad, ry, w - pad * 2 - 4, areaTop, areaTop + areaH, "Auto movement",
				"Off (default): while you are connected you walk every creature yourself on its turn - click a square, then End turn in the tracker. On: creatures take their own turns. Each Actor can also be set to DM control in the Actors tab.",
				DmState.autoMovement, () -> ClientPlayNetworking.send(new DmActionPayload(1, DmState.autoMovement ? 0 : 1)));
		ry += tool(g, x + pad, ry, w - pad * 2 - 4, areaTop, areaTop + areaH, "Pause game",
				"On: players can not walk or touch blocks (breaking, placing, using); you still can. A player in a fight keeps using the grid. Looking around, the sheet and chat keep working.",
				DmState.paused, () -> ClientPlayNetworking.send(new DmActionPayload(2, DmState.paused ? 0 : 1)));
		ry += opener(g, x + pad, ry, w - pad * 2 - 4, areaTop, areaTop + areaH, "Stage",
				"Click an Actor in the world to select it, drag it to walk it, possess it, and take control of it in a fight. Also G or /stage.",
				() -> {
					MinecraftClient.getInstance().setScreen(null);
					ActorStageScreen.requestOpen();
				});
		ry += tool(g, x + pad, ry, w - pad * 2 - 4, areaTop, areaTop + areaH, "Combat tracker",
				"The compact list of the fight at the corner of the screen.",
				TrackerPanel.visible(), TrackerPanel::toggleHidden);
		g.disableScissor();
		toolHeight = ry - start;
		int overflow = Math.max(0, toolHeight - areaH);
		toolScroll = MathHelper.clamp(toolScroll, 0, overflow);
		if (overflow > 0) {
			int barH = Math.max(8, Math.round(areaH * (areaH / (float) toolHeight)));
			int barY = areaTop + Math.round((areaH - barH) * (toolScroll / (float) overflow));
			g.fill(x + w - 4, barY, x + w - 2, barY + barH, 0xFF5A616C);
		}
	}

	private static final int TOOL_H = 22;

	/** The name of a tool and its ? help button. Returns nothing; the help text is the button's tooltip. */
	private void toolHead(DrawContext g, int x, int y, int clipTop, int clipBottom, String name, String help, int stripe) {
		g.drawText(textRenderer, name, x + 8, y + 7, 0xFFE6E8EB, false);
		int qx = x + 8 + tw(name) + 5;
		boolean visible = y + TOOL_H > clipTop && y < clipBottom;
		boolean over = visible && hit(qx, y + 5, 11, 11, () -> {}, help);
		g.fill(qx, y + 5, qx + 11, y + 16, over ? gold : 0xFF3B414C);
		g.fill(qx + 1, y + 6, qx + 10, y + 15, over ? 0xFF6B4E12 : panel);
		g.drawCenteredTextWithShadow(textRenderer, "?", qx + 5, y + 7, over ? 0xFFFFFFFF : muted);
	}

	/** A tool row with an Open button instead of a switch. Returns the height used. */
	private int opener(DrawContext g, int x, int y, int w, int clipTop, int clipBottom, String name, String help, Runnable open) {
		g.fill(x, y, x + w, y + TOOL_H, panel);
		g.fill(x, y, x + 2, y + TOOL_H, gold);
		toolHead(g, x, y, clipTop, clipBottom, name, help, gold);
		if (y + TOOL_H > clipTop && y < clipBottom) button(g, x + w - tw("Open") - 14, y + 5, "Open", open, null, 0xFF6B4E12, gold);
		return TOOL_H + 3;
	}

	/** One tool row: a name, its ? help and an On / Off switch. Returns the height used. */
	private int tool(DrawContext g, int x, int y, int w, int clipTop, int clipBottom, String name, String help, boolean on, Runnable toggle) {
		g.fill(x, y, x + w, y + TOOL_H, panel);
		g.fill(x, y, x + 2, y + TOOL_H, on ? 0xFF3CB84A : 0xFF7A1C27);
		toolHead(g, x, y, clipTop, clipBottom, name, help, on ? 0xFF3CB84A : 0xFF7A1C27);
		if (y + TOOL_H > clipTop && y < clipBottom) {
			String label = on ? "On" : "Off";
			int bw = 36;
			int bx = x + w - bw - 6;
			boolean over = hit(bx, y + 4, bw, 14, toggle, "Click to switch");
			g.fill(bx, y + 4, bx + bw, y + 18, on ? 0xFF3CB84A : 0xFFB8323F);
			g.fill(bx + 1, y + 5, bx + bw - 1, y + 17, over ? panelHover : on ? 0xFF1E4A25 : 0xFF4A1219);
			g.drawCenteredTextWithShadow(textRenderer, label, bx + bw / 2, y + 8, 0xFFFFFFFF);
		}
		return TOOL_H + 3;
	}

	/** The corner mark that shows the window can be resized: a small triangle of dots. */
	private void drawGrip(DrawContext g, int x, int y, int w, int h) {
		int cx = x + w - 3;
		int cy = y + h - 3;
		for (int i = 0; i < 3; i++) {
			for (int k = 0; k < 3 - i; k++) g.fill(cx - 3 * i, cy - 3 * k, cx - 3 * i + 1, cy - 3 * k + 1, 0xFF6B717C);
		}
	}

	private void drawTooltip(DrawContext g, int mx, int my) {
		String tooltip = null;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.tip != null && hit.contains(mx, my)) {
				tooltip = hit.tip;
				break;
			}
		}
		if (tooltip != null) g.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(tooltip), Math.min(230, width - 20)), mx, my);
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

	/** Which resize zone the point is in: 1 right edge, 2 bottom edge, 3 corner, 0 none. */
	private int resizeZone(double mx, double my) {
		int x = winX, y = winY;
		boolean right = mx >= x + curW - GRAB && mx <= x + curW + 2 && my >= y && my <= y + curH + 2;
		boolean bottom = my >= y + curH - GRAB && my <= y + curH + 2 && mx >= x && mx <= x + curW + 2;
		if (right && bottom) return 3;
		if (right) return 1;
		if (bottom) return 2;
		return 0;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			int zone = resizeZone(mx, my);
			if (zone != 0) {
				resizeMode = zone;
				resizeMouseX = (int) mx;
				resizeMouseY = (int) my;
				resizeW = curW;
				resizeH = curH;
				return true;
			}
			if (tab == 2 && ServerCharacters.isDm()) actors.blur();
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				if (hit.contains(mx, my)) {
					hit.action.run();
					return true;
				}
			}
			if (mx >= winX && mx < winX + curW && my >= winY && my < winY + TITLE_H) {
				dragging = true;
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (resizeMode != 0) {
			if ((resizeMode & 1) != 0) SIZE[tab][0] = MathHelper.clamp(resizeW + (int) mx - resizeMouseX, MIN[tab][0], Math.max(MIN[tab][0], width - 8));
			if ((resizeMode & 2) != 0) SIZE[tab][1] = MathHelper.clamp(resizeH + (int) my - resizeMouseY, MIN[tab][1], Math.max(MIN[tab][1], height - 8));
			return true;
		}
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
		resizeMode = 0;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (ServerCharacters.isDm() && tab == 1) {
			toolScroll = Math.max(0, toolScroll - (int) Math.signum(vertical) * 12);
			return true;
		}
		if (ServerCharacters.isDm() && tab == 2) return actors.scrolled(mx, my, vertical);
		scroll = Math.max(0, scroll - (int) Math.signum(vertical));
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (ServerCharacters.isDm() && tab == 2 && actors.charTyped(chr)) return true;
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (ServerCharacters.isDm() && tab == 2 && actors.typing()) {
			actors.keyPressed(keyCode, modifiers); // a text box swallows every key: typing J or Esc must not close the window
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE || (ServerCharacters.isDm() && TacticalCombatClient.ENCOUNTER_KEY.matchesKey(keyCode, scanCode))) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
