package dev.tacticalcombat.client;

import dev.tacticalcombat.net.ActorActionPayload;
import dev.tacticalcombat.net.DmStatePayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Actors tab of the DM window. An Actor is a named creature or person of the world that the DM places like a
 * token: it has a sheet, a model (a Minecraft creature, a player skin, an item or a block), a side (hostile, neutral,
 * friendly) and can be controlled by the DM in a fight. The list is on the left, the selected Actor (or the form for
 * a new one) on the right. Everything is done on the server; this tab only sends requests. It is drawn with the DM
 * window's own buttons and text boxes, and the right side scrolls when the window is small.
 */
final class ActorsPanel {
	private static final int ROW_H = 24;
	private static final String[] KINDS = {"mob", "skin", "item", "block"};
	private static final String[] KIND_LABEL = {"Creature", "Skin", "Item", "Block"};
	private static final String[] KIND_HINT = {"minecraft:wolf", "player name or texture id", "minecraft:diamond_sword", "minecraft:chest"};
	private static final String[] SIDES = {"Hostile", "Neutral", "Friendly"};

	private static String selectedId = "";

	private boolean creating;
	private int listScroll;
	private int formScroll;
	private int formHeight;
	private boolean deleteArmed;
	private long deleteArmedAt;
	private String loadedFor = "";

	private final TextInput nameInput = new TextInput("Name", 40);
	private final TextInput valueInput = new TextInput("", 100);
	private int kindIdx;
	private int sheetIdx;
	private boolean linked;
	private int side;

	private int clipTop, clipBottom;
	private int areaX, areaY, areaW, areaH;
	private int listX, listY, listW, listH;

	// ------------------------------------------------------------------ data

	private static DmStatePayload.ActorInfo selected() {
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.id().equals(selectedId)) return a;
		return null;
	}

	/** Sheets an Actor can use: every character the server told this DM about, except the private copies of Actors. */
	private static List<CharacterData> sheets() {
		Set<String> owned = new HashSet<>();
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.ownsSheet()) owned.add(a.sheetId());
		List<CharacterData> out = new ArrayList<>();
		for (CharacterData c : SheetLibrary.CHARACTERS) if (!c.remote && !c.link.isEmpty() && !owned.contains(c.link)) out.add(c);
		for (CharacterData c : ServerCharacters.remotes()) if (!c.link.isEmpty() && !owned.contains(c.link)) out.add(c);
		out.sort((a, b) -> {
			int k = Boolean.compare(!"npc".equals(a.kind), !"npc".equals(b.kind));
			return k != 0 ? k : a.displayName().compareToIgnoreCase(b.displayName());
		});
		return out;
	}

	private static String sheetName(String id) {
		CharacterData c = CharacterSheetScreen.find(id, "");
		return c == null ? "(unknown sheet)" : c.displayName();
	}

	private static void send(ActorActionPayload p) {
		ClientPlayNetworking.send(p);
	}

	private static String modelText(int kind, String value) {
		return KINDS[kind] + ":" + value.trim();
	}

	private static int kindIndex(String kind) {
		for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
		return 0;
	}

	private void load(DmStatePayload.ActorInfo a) {
		loadedFor = a.id();
		nameInput.text = a.name();
		kindIdx = kindIndex(a.kind());
		valueInput.text = a.value();
		sheetIdx = 0;
		linked = !a.ownsSheet();
		deleteArmed = false;
		formScroll = 0;
	}

	private void startCreating() {
		creating = true;
		nameInput.text = "";
		valueInput.text = "";
		kindIdx = 0;
		side = 0;
		linked = false;
		sheetIdx = 0;
		formScroll = 0;
	}

	// ------------------------------------------------------------------ drawing helpers

	/** A button, registered for clicks only while it is inside the visible part of the tab. */
	private int btn(EncounterScreen ui, DrawContext g, int x, int y, String text, Runnable action, String tip, int fill, int edge) {
		if (y + 12 <= clipTop || y >= clipBottom) return ui.tw(text) + 8;
		return ui.button(g, x, y, text, action, tip, fill, edge);
	}

	private int plain(EncounterScreen ui, DrawContext g, int x, int y, String text, Runnable action, String tip) {
		return btn(ui, g, x, y, text, action, tip, ui.panel, 0xFF3B414C);
	}

	private int gold(EncounterScreen ui, DrawContext g, int x, int y, String text, Runnable action, String tip) {
		return btn(ui, g, x, y, text, action, tip, 0xFF6B4E12, ui.gold);
	}

	private void input(EncounterScreen ui, DrawContext g, TextInput in, int x, int y, int w) {
		in.draw(g, ui.font(), x, y, w, 14, 0xFF3B414C, 0xFF0F1117);
		if (y + 14 > clipTop && y < clipBottom) {
			ui.hit(x, y, w, 14, () -> {
				nameInput.focused = in == nameInput;
				valueInput.focused = in == valueInput;
			}, null);
		}
	}

	private void label(EncounterScreen ui, DrawContext g, String text, int x, int y) {
		g.drawText(ui.font(), text, x, y + 3, ui.muted, false);
	}

	// ------------------------------------------------------------------ drawing

	void draw(EncounterScreen ui, DrawContext g, int x, int y, int w, int h) {
		if (deleteArmed && System.currentTimeMillis() - deleteArmedAt > 3000) deleteArmed = false;
		DmStatePayload.ActorInfo a = creating ? null : selected();
		if (a != null && !loadedFor.equals(a.id())) load(a);

		// ---- list
		listX = x + 6;
		listY = y + 6;
		listW = MathHelper.clamp(w / 3, 100, 150);
		listH = h - 12 - 18;
		g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, ui.edge);
		g.fill(listX, listY, listX + listW, listY + listH, 0xFF0B0C10);
		List<DmStatePayload.ActorInfo> list = DmState.actors;
		int rows = Math.max(1, listH / ROW_H);
		listScroll = MathHelper.clamp(listScroll, 0, Math.max(0, list.size() - rows));
		g.enableScissor(listX, listY, listX + listW, listY + listH);
		if (list.isEmpty()) g.drawText(ui.font(), "No Actors yet.", listX + 6, listY + 6, ui.dim, false);
		for (int i = 0; i < rows && i + listScroll < list.size(); i++) {
			DmStatePayload.ActorInfo e = list.get(i + listScroll);
			int ry = listY + i * ROW_H;
			boolean sel = !creating && e.id().equals(selectedId);
			boolean over = ui.hit(listX, ry, listW, ROW_H - 1, () -> {
				selectedId = e.id();
				creating = false;
				loadedFor = "";
				nameInput.focused = false;
				valueInput.focused = false;
			}, null);
			g.fill(listX, ry, listX + listW, ry + ROW_H - 1, sel ? ui.panelHover : over ? ui.panel : 0xFF11131A);
			int sideColor = e.disposition() == 0 ? 0xFFC83232 : e.disposition() == 1 ? 0xFFC9A227 : 0xFF3CB84A;
			g.fill(listX, ry, listX + 2, ry + ROW_H - 1, sideColor);
			String tag = e.inFight() ? "fighting" : e.entityId() >= 0 ? "placed" : "not placed";
			int tagW = ui.tw(tag);
			g.drawText(ui.font(), ui.font().trimToWidth(e.name(), Math.max(10, listW - tagW - 14)), listX + 6, ry + 3, 0xFFE6E8EB, false);
			g.drawText(ui.font(), tag, listX + listW - tagW - 4, ry + 3, e.inFight() ? ui.gold : ui.dim, false);
			float frac = e.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, e.hp() / e.max()));
			int barW = Math.min(70, listW - 50);
			g.fill(listX + 6, ry + 15, listX + 6 + barW, ry + 18, 0xFF05060A);
			g.fill(listX + 6, ry + 15, listX + 6 + Math.round(barW * frac), ry + 18, sideColor);
			g.drawText(ui.font(), Math.round(e.hp()) + "/" + Math.round(e.max()), listX + 10 + barW, ry + 12, ui.muted, false);
		}
		g.disableScissor();
		int newW = listW;
		boolean newOver = ui.hit(listX, y + h - 18, newW, 12, this::startCreating, "Make a new Actor");
		g.fill(listX, y + h - 18, listX + newW, y + h - 6, ui.gold);
		g.fill(listX + 1, y + h - 17, listX + newW - 1, y + h - 7, newOver ? ui.panelHover : 0xFF6B4E12);
		g.drawCenteredTextWithShadow(ui.font(), "+ New actor", listX + newW / 2, y + h - 16, 0xFFFFFFFF);

		// ---- right side
		areaX = listX + listW + 8;
		areaY = y + 6;
		areaW = x + w - 6 - areaX;
		areaH = h - 12;
		if (areaW < 60) return;
		clipTop = areaY;
		clipBottom = areaY + areaH;
		g.enableScissor(areaX - 2, clipTop, areaX + areaW + 2, clipBottom);
		int start = areaY - formScroll;
		int cy = start;
		if (creating) cy = drawCreate(ui, g, areaX, cy, areaW);
		else if (a == null) {
			g.drawText(ui.font(), "Pick an Actor on the left, or make a new one.", areaX, cy + 2, ui.dim, false);
			cy += 14;
		} else cy = drawEdit(ui, g, a, areaX, cy, areaW);
		g.disableScissor();
		formHeight = cy - start;
		int overflow = Math.max(0, formHeight - areaH);
		formScroll = MathHelper.clamp(formScroll, 0, overflow);
		if (overflow > 0) {
			int barH = Math.max(8, Math.round(areaH * (areaH / (float) formHeight)));
			int barY = areaY + Math.round((areaH - barH) * (formScroll / (float) overflow));
			g.fill(areaX + areaW, barY, areaX + areaW + 2, barY + barH, 0xFF5A616C);
		}
	}

	private int hints(EncounterScreen ui, DrawContext g, int x, int y, int w, String... lines) {
		for (String line : lines) {
			for (OrderedText l : ui.font().wrapLines(Text.literal(line), w)) {
				if (y + 9 > clipTop && y < clipBottom) g.drawText(ui.font(), l, x, y, ui.dim, false);
				y += 10;
			}
			y += 2;
		}
		return y;
	}

	private int drawCreate(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		int cx = x + 44;
		int cw = w - 44;
		g.drawText(ui.font(), "New actor", x, y + 2, ui.gold, false);
		y += 16;

		label(ui, g, "Name", x, y);
		input(ui, g, nameInput, cx, y, Math.max(40, cw));
		y += 20;

		List<CharacterData> sheets = sheets();
		sheetIdx = sheets.isEmpty() ? 0 : MathHelper.clamp(sheetIdx, 0, sheets.size() - 1);
		label(ui, g, "Sheet", x, y);
		String sheetLabel = sheets.isEmpty() ? "No sheets yet" : sheets.get(sheetIdx).displayName();
		plain(ui, g, cx, y, ui.font().trimToWidth(sheetLabel, Math.max(30, cw - 8)), () -> {
			if (!sheets.isEmpty()) sheetIdx = (sheetIdx + 1) % sheets.size();
		}, sheets.isEmpty() ? "Link a character to the server first (Edit page of a sheet)" : "Click for the next sheet");
		y += 16;
		plain(ui, g, cx, y, linked ? "Shared" : "Own copy", () -> linked = !linked,
				"Own copy: its hit points are its own. Shared: it uses the character itself.");
		y += 20;

		label(ui, g, "Model", x, y);
		y = modelRow(ui, g, x, y, w, null);

		label(ui, g, "Side", x, y);
		plain(ui, g, cx, y, SIDES[side], () -> side = (side + 1) % 3, "Hostile Actors near the party join an encounter");
		y += 24;

		gold(ui, g, x, y, "Create", () -> {
			if (sheets.isEmpty()) return;
			send(new ActorActionPayload(ActorActionPayload.CREATE, "", nameInput.text.trim(), sheets.get(sheetIdx).link,
					modelText(kindIdx, valueInput.text), (linked ? 1 : 0) | (side << 1)));
			creating = false;
			selectedId = "";
			loadedFor = "";
		}, sheets.isEmpty() ? "No sheet to give it yet" : "Make the Actor (then place it)");
		plain(ui, g, x + 48, y, "Cancel", () -> creating = false, null);
		y += 20;
		return hints(ui, g, x, y, w, "Tip: Own copy for monsters, Shared for named characters.");
	}

	/** Kind button, then the value box (and an Apply button when {@code apply} is given). Returns the next y. */
	private int modelRow(EncounterScreen ui, DrawContext g, int x, int y, int w, Runnable apply) {
		int cx = x + 44;
		int kw = plain(ui, g, cx, y, KIND_LABEL[kindIdx], () -> kindIdx = (kindIdx + 1) % KINDS.length, "Creature, player Skin, floating Item or Block");
		int applyW = apply == null ? 0 : ui.tw("Apply") + 8;
		int vx = cx + kw + 4;
		int vw = Math.max(40, x + w - vx - (apply == null ? 0 : applyW + 4));
		TextInput shown = valueInput;
		input(ui, g, shown, vx, y, vw);
		if (valueInput.text.isEmpty() && !valueInput.focused && y + 14 > clipTop && y < clipBottom) {
			g.drawText(ui.font(), ui.font().trimToWidth(KIND_HINT[kindIdx], vw - 6), vx + 3, y + 3, 0xFF6B717C, false);
		}
		if (apply != null) gold(ui, g, vx + vw + 4, y, "Apply", apply, "Change the look of the placed Actor");
		return y + 20;
	}

	private int drawEdit(EncounterScreen ui, DrawContext g, DmStatePayload.ActorInfo a, int x, int y, int w) {
		int cx = x + 44;
		int cw = w - 44;
		final String id = a.id();
		boolean inFight = a.inFight();
		String head = a.name() + "  -  " + sheetName(a.sheetId()) + (a.ownsSheet() ? " (own copy)" : " (shared)");
		g.drawText(ui.font(), ui.font().trimToWidth(head, w), x, y + 2, ui.gold, false);
		y += 16;

		label(ui, g, "Name", x, y);
		int renameW = ui.tw("Rename") + 8;
		input(ui, g, nameInput, cx, y, Math.max(40, cw - renameW - 4));
		gold(ui, g, cx + Math.max(40, cw - renameW - 4) + 4, y, "Rename", () ->
				send(new ActorActionPayload(ActorActionPayload.RENAME, id, nameInput.text.trim(), "", "", 0)), "Rename the Actor");
		y += 20;

		List<CharacterData> sheets = sheets();
		sheetIdx = sheets.isEmpty() ? 0 : MathHelper.clamp(sheetIdx, 0, sheets.size() - 1);
		label(ui, g, "Sheet", x, y);
		int ow = plain(ui, g, cx, y, "Open sheet", () -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc != null) CharacterSheetScreen.open(mc, mc.currentScreen, a.sheetId(), "");
		}, "Open this Actor's sheet");
		plain(ui, g, cx + ow + 4, y, ui.font().trimToWidth(sheets.isEmpty() ? "-" : sheets.get(sheetIdx).displayName(), Math.max(30, cw - ow - 12)),
				() -> {
					if (!sheets.isEmpty()) sheetIdx = (sheetIdx + 1) % sheets.size();
				}, "Pick another sheet (then press Set)");
		y += 16;
		int tw = plain(ui, g, cx, y, linked ? "Shared" : "Own copy", () -> linked = !linked,
				"Own copy: its hit points are its own. Shared: it uses the character itself.");
		gold(ui, g, cx + tw + 4, y, "Set", () -> {
			if (sheets.isEmpty() || inFight) return;
			send(new ActorActionPayload(ActorActionPayload.SHEET, id, "", sheets.get(sheetIdx).link, "", linked ? 1 : 0));
		}, inFight ? "Not while it is in a fight" : "Give the Actor the picked sheet");
		y += 20;

		label(ui, g, "Model", x, y);
		y = modelRow(ui, g, x, y, w, () ->
				send(new ActorActionPayload(ActorActionPayload.MODEL, id, "", "", modelText(kindIdx, valueInput.text), 0)));

		label(ui, g, "Side", x, y);
		int sw = plain(ui, g, cx, y, SIDES[a.disposition()], () ->
				send(new ActorActionPayload(ActorActionPayload.DISPOSITION, id, "", "", "", (a.disposition() + 1) % 3)),
				"Click to change the side");
		int controlX = cx + sw + 4;
		String control = a.dmControl() ? "DM controls it" : "Takes its own turns";
		if (controlX + ui.tw(control) + 8 > x + w) { // no room beside it: next line
			y += 16;
			controlX = cx;
		}
		btn(ui, g, controlX, y, control, () ->
				send(new ActorActionPayload(ActorActionPayload.CONTROL, id, "", "", "", a.dmControl() ? 0 : 1)),
				"Whether you walk it in a fight or it takes its own turns", a.dmControl() ? 0xFF6B4E12 : ui.panel, a.dmControl() ? ui.gold : 0xFF3B414C);
		y += 24;

		int bx = x;
		bx += gold(ui, g, bx, y, a.entityId() >= 0 ? "Move here" : "Place", () -> send(ActorActionPayload.of(ActorActionPayload.SPAWN, id)),
				"Put it where your crosshair points") + 4;
		bx += plain(ui, g, bx, y, "Recall", () -> send(ActorActionPayload.of(ActorActionPayload.RECALL, id)), "Take it out of the world") + 4;
		plain(ui, g, bx, y, "To fight", () -> send(ActorActionPayload.of(ActorActionPayload.FIGHT, id)), "Add it to the running fight");
		y += 18;
		int dw = plain(ui, g, x, y, "Duplicate", () -> send(ActorActionPayload.of(ActorActionPayload.DUPLICATE, id)), "Make another one like it");
		btn(ui, g, x + dw + 4, y, deleteArmed ? "Really delete?" : "Delete", () -> {
			if (!deleteArmed) {
				deleteArmed = true;
				deleteArmedAt = System.currentTimeMillis();
				return;
			}
			deleteArmed = false;
			send(ActorActionPayload.of(ActorActionPayload.DELETE, id));
			selectedId = "";
			loadedFor = "";
		}, "Click twice to delete", 0xFF7A1C27, 0xFFB8323F);
		y += 24;
		return hints(ui, g, x, y, w,
				"Hostile Actors near the party join when you start an encounter; use To fight for others.",
				"Shared sheets share hit points between every Actor that uses them.");
	}

	// ------------------------------------------------------------------ input

	boolean typing() {
		return nameInput.focused || valueInput.focused;
	}

	boolean charTyped(char c) {
		return nameInput.charTyped(c) || valueInput.charTyped(c);
	}

	boolean keyPressed(int keyCode, int modifiers) {
		return nameInput.keyPressed(keyCode, modifiers) || valueInput.keyPressed(keyCode, modifiers);
	}

	/** A click that landed outside any text box takes the focus away (called after the hits ran). */
	void blur() {
		nameInput.focused = false;
		valueInput.focused = false;
	}

	boolean scrolled(double mx, double my, double vertical) {
		if (mx >= listX && mx < listX + listW) {
			listScroll = Math.max(0, listScroll - (int) Math.signum(vertical));
			return true;
		}
		formScroll = Math.max(0, formScroll - (int) Math.signum(vertical) * 12);
		return true;
	}
}
