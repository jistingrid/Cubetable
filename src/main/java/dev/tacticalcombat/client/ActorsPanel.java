package dev.tacticalcombat.client;

import dev.tacticalcombat.actor.ActorService;
import dev.tacticalcombat.net.ActorActionPayload;
import dev.tacticalcombat.net.DmStatePayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Actors tab of the DM window. An Actor is a named creature or person of the world that the DM places like a
 * token: it has a sheet, a model (a Minecraft creature, a player skin, an item or a block), a side (hostile, neutral,
 * friendly) and can be controlled by the DM in a fight. The list is on the left, the selected Actor (or folder, or the
 * form for a new one, or the import view) on the right. Actors are filed in folders and carry tags; the search box
 * finds by name, folder or "#tag". Import turns existing sheets into Actors in bulk. Everything is done on the server;
 * this tab only sends requests. It is drawn with the DM window's own buttons and text boxes, and both sides scroll.
 */
final class ActorsPanel {
	private static final int ROW_H = 24;
	private static final int FOLDER_H = 14;
	private static final String[] KINDS = {"mob", "skin", "item", "block"};
	private static final String[] KIND_LABEL = {"Creature", "Skin", "Item", "Block"};
	private static final String[] KIND_HINT = {"minecraft:wolf", "player name or texture id", "minecraft:diamond_sword", "minecraft:chest"};
	private static final String[] SIDES = {"Hostile", "Neutral", "Friendly"};
	private static final int BROWSE = 0, CREATE = 1, IMPORT = 2;

	private static String selectedId = "";
	private static String selectedFolder = "";
	private static final Set<String> collapsed = new HashSet<>();

	/** What the right side shows when no Actor or folder is picked: the hint, the new-Actor form or the import view. */
	private int mode;
	private int listScroll;
	private int listContent;
	private int formScroll;
	private int formHeight;
	private boolean deleteArmed;
	private long deleteArmedAt;
	private String loadedFor = "";
	private String loadedFolder = "";

	private final TextInput nameInput = new TextInput("Name", 40);
	private final TextInput valueInput = new TextInput("", 100);
	private final TextInput search = new TextInput("Search name, folder or #tag", 40);
	private final TextInput tagInput = new TextInput("New tag", 20);
	private final TextInput folderInput = new TextInput("Folder name", 32);
	private final TextInput bulkTags = new TextInput("Tags (a, b, c)", 60);
	private final TextInput importFilter = new TextInput("Filter sheets", 40);
	private final TextInput[] inputs = {nameInput, valueInput, search, tagInput, folderInput, bulkTags, importFilter};
	private String folderPick = "";
	private final Set<String> picked = new LinkedHashSet<>();
	private int kindIdx;
	private int sheetIdx;
	private boolean linked;
	private int side;

	private int clipTop, clipBottom;
	private int areaX, areaY, areaW, areaH;
	private int listX, listY, listW, listH;

	/** One line of the left list: a folder header, an "Unfiled" label or an Actor. */
	private record Row(String folder, DmStatePayload.ActorInfo actor, int count, boolean label) {}

	// ------------------------------------------------------------------ data

	private static DmStatePayload.ActorInfo selected() {
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.id().equals(selectedId)) return a;
		return null;
	}

	private static List<String> tagsOf(DmStatePayload.ActorInfo a) {
		List<String> out = new ArrayList<>();
		for (String t : a.tags().split(",")) if (!t.isBlank()) out.add(t.trim());
		return out;
	}

	private static boolean hasTag(List<String> tags, String tag) {
		for (String t : tags) if (t.equalsIgnoreCase(tag)) return true;
		return false;
	}

	/** Every tag any Actor has, alphabetical. */
	private static List<String> allTags() {
		List<String> out = new ArrayList<>();
		for (DmStatePayload.ActorInfo a : DmState.actors) for (String t : tagsOf(a)) if (!hasTag(out, t)) out.add(t);
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	private static boolean folderExists(String name) {
		for (String f : DmState.folders) if (f.equalsIgnoreCase(name)) return true;
		return false;
	}

	private static boolean matches(DmStatePayload.ActorInfo a, String query) {
		if (query.isEmpty()) return true;
		String q = query.toLowerCase(Locale.ROOT);
		if (q.startsWith("#")) {
			String want = q.substring(1).trim();
			if (want.isEmpty()) return !a.tags().isEmpty();
			for (String t : tagsOf(a)) if (t.toLowerCase(Locale.ROOT).contains(want)) return true;
			return false;
		}
		if (a.name().toLowerCase(Locale.ROOT).contains(q) || a.folder().toLowerCase(Locale.ROOT).contains(q)) return true;
		for (String t : tagsOf(a)) if (t.toLowerCase(Locale.ROOT).contains(q)) return true;
		return false;
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

	/** A sheet called "Wolf" becomes a wolf; anything Minecraft has no creature for becomes a villager. */
	private static String guessModel(CharacterData c) {
		String n = c.displayName().toLowerCase(Locale.ROOT).replaceAll("\\s+\\d+$", "").trim().replace(' ', '_');
		Identifier id = n.isEmpty() ? null : Identifier.tryParse("minecraft:" + n);
		if (id != null && Registries.ENTITY_TYPE.containsId(id) && ActorService.checkModel("mob", id.toString()) == null) return "mob:" + id;
		return "mob:minecraft:villager";
	}

	private static String nextFolder(String current) {
		List<String> opts = new ArrayList<>();
		opts.add("");
		opts.addAll(DmState.folders);
		int i = 0;
		for (int k = 0; k < opts.size(); k++) if (opts.get(k).equalsIgnoreCase(current)) i = k;
		return opts.get((i + 1) % opts.size());
	}

	private static String folderLabel(String f) {
		return f.isEmpty() ? "(no folder)" : f;
	}

	private void load(DmStatePayload.ActorInfo a) {
		loadedFor = a.id();
		nameInput.text = a.name();
		kindIdx = kindIndex(a.kind());
		valueInput.text = a.value();
		tagInput.text = "";
		sheetIdx = 0;
		linked = !a.ownsSheet();
		deleteArmed = false;
		formScroll = 0;
	}

	private void startCreating() {
		mode = CREATE;
		selectedId = "";
		selectedFolder = "";
		nameInput.text = "";
		valueInput.text = "";
		bulkTags.text = "";
		folderPick = "";
		kindIdx = 0;
		side = 0;
		linked = false;
		sheetIdx = 0;
		formScroll = 0;
	}

	private void startImport() {
		mode = IMPORT;
		selectedId = "";
		selectedFolder = "";
		picked.clear();
		importFilter.text = "";
		bulkTags.text = "";
		folderPick = "";
		side = 0;
		linked = false;
		formScroll = 0;
	}

	private void newFolder() {
		String name = "New folder";
		for (int i = 2; folderExists(name); i++) name = "New folder " + i;
		send(new ActorActionPayload(ActorActionPayload.FOLDER_NEW, "", name, "", "", 0));
		selectedFolder = name;
		selectedId = "";
		loadedFolder = "";
		mode = BROWSE;
		collapsed.remove(name);
	}

	private void unfocus() {
		for (TextInput t : inputs) t.focused = false;
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
				for (TextInput t : inputs) t.focused = t == in;
			}, null);
		}
	}

	private void label(EncounterScreen ui, DrawContext g, String text, int x, int y) {
		g.drawText(ui.font(), text, x, y + 3, ui.muted, false);
	}

	/** A hit area limited to the visible part of the left list. */
	private boolean listHit(EncounterScreen ui, int x, int y, int w, int h, Runnable action, String tip) {
		int y0 = Math.max(y, listY);
		int y1 = Math.min(y + h, listY + listH);
		return y1 > y0 && ui.hit(x, y0, w, y1 - y0, action, tip);
	}

	// ------------------------------------------------------------------ the list (left)

	private List<Row> rows() {
		List<Row> out = new ArrayList<>();
		String q = search.text.trim();
		if (!q.isEmpty()) { // a search is a flat list of what matches
			for (DmStatePayload.ActorInfo a : DmState.actors) if (matches(a, q)) out.add(new Row("", a, 0, false));
			return out;
		}
		for (String f : DmState.folders) {
			int n = 0;
			for (DmStatePayload.ActorInfo a : DmState.actors) if (a.folder().equalsIgnoreCase(f)) n++;
			out.add(new Row(f, null, n, false));
			if (collapsed.contains(f)) continue;
			for (DmStatePayload.ActorInfo a : DmState.actors) if (a.folder().equalsIgnoreCase(f)) out.add(new Row(f, a, 0, false));
		}
		boolean header = !DmState.folders.isEmpty();
		for (DmStatePayload.ActorInfo a : DmState.actors) {
			if (!a.folder().isEmpty() && folderExists(a.folder())) continue;
			if (header) {
				out.add(new Row("", null, 0, true));
				header = false;
			}
			out.add(new Row("", a, 0, false));
		}
		return out;
	}

	private static int rowHeight(Row r) {
		return r.actor() != null ? ROW_H : FOLDER_H;
	}

	private void selectActor(DmStatePayload.ActorInfo e) {
		selectedId = e.id();
		selectedFolder = "";
		mode = BROWSE;
		loadedFor = "";
		unfocus();
	}

	private void drawList(EncounterScreen ui, DrawContext g, int x, int y, int h) {
		int sy = y + 6;
		search.draw(g, ui.font(), listX, sy, listW, 14, 0xFF3B414C, 0xFF0F1117);
		ui.hit(listX, sy, listW, 14, () -> {
			for (TextInput t : inputs) t.focused = t == search;
		}, "Type a name or folder, or #tag to find by tag");
		listY = y + 24;
		listH = y + h - 38 - listY;
		g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, ui.edge);
		g.fill(listX, listY, listX + listW, listY + listH, 0xFF0B0C10);

		List<Row> rows = rows();
		int total = 0;
		for (Row r : rows) total += rowHeight(r);
		listContent = total;
		listScroll = MathHelper.clamp(listScroll, 0, Math.max(0, total - listH));
		g.enableScissor(listX, listY, listX + listW, listY + listH);
		if (rows.isEmpty()) {
			g.drawText(ui.font(), search.text.isBlank() ? "No Actors yet." : "Nothing found.", listX + 6, listY + 6, ui.dim, false);
		}
		int ry = listY - listScroll;
		for (Row r : rows) {
			int rh = rowHeight(r);
			if (ry + rh > listY && ry < listY + listH) drawRow(ui, g, r, ry, rh);
			ry += rh;
		}
		g.disableScissor();
		if (total > listH) {
			int barH = Math.max(8, Math.round(listH * (listH / (float) total)));
			int barY = listY + Math.round((listH - barH) * (listScroll / (float) (total - listH)));
			g.fill(listX + listW - 2, barY, listX + listW, barY + barH, 0xFF5A616C);
		}

		int by = y + h - 32;
		int bx = listX;
		bx += btn(ui, g, bx, by, "+ Actor", this::startCreating, "Make a new Actor", 0xFF6B4E12, ui.gold) + 4;
		btn(ui, g, bx, by, "+ Folder", this::newFolder, "Make an empty folder", ui.panel, 0xFF3B414C);
		btn(ui, g, listX, by + 15, "Import sheets...", this::startImport, "Turn existing sheets into Actors, in bulk", ui.panel, 0xFF3B414C);
	}

	private void drawRow(EncounterScreen ui, DrawContext g, Row r, int ry, int rh) {
		if (r.actor() == null) {
			if (r.label()) {
				g.drawText(ui.font(), "No folder", listX + 6, ry + 3, ui.dim, false);
				return;
			}
			boolean closed = collapsed.contains(r.folder());
			boolean sel = r.folder().equalsIgnoreCase(selectedFolder) && selectedId.isEmpty() && mode == BROWSE;
			final String f = r.folder();
			boolean arrowOver = listHit(ui, listX, ry, 14, rh, () -> {
				if (!collapsed.remove(f)) collapsed.add(f);
			}, closed ? "Open the folder" : "Close the folder");
			boolean over = listHit(ui, listX + 14, ry, listW - 14, rh, () -> {
				selectedFolder = f;
				selectedId = "";
				mode = BROWSE;
				loadedFolder = "";
				unfocus();
			}, "Click to rename or delete the folder");
			g.fill(listX, ry, listX + listW, ry + rh - 1, sel ? ui.panelHover : over || arrowOver ? ui.panel : 0xFF161A22);
			g.drawText(ui.font(), closed ? "+" : "-", listX + 4, ry + 3, ui.gold, false);
			String head = f + " (" + r.count() + ")";
			g.drawText(ui.font(), ui.font().trimToWidth(head, listW - 20), listX + 14, ry + 3, ui.gold, false);
			return;
		}
		DmStatePayload.ActorInfo e = r.actor();
		int indent = !search.text.isBlank() || r.folder().isEmpty() ? 0 : 6;
		boolean sel = mode == BROWSE && e.id().equals(selectedId);
		boolean over = listHit(ui, listX + indent, ry, listW - indent, rh - 1, () -> selectActor(e), null);
		int left = listX + indent;
		g.fill(left, ry, listX + listW, ry + rh - 1, sel ? ui.panelHover : over ? ui.panel : 0xFF11131A);
		int sideColor = e.disposition() == 0 ? 0xFFC83232 : e.disposition() == 1 ? 0xFFC9A227 : 0xFF3CB84A;
		g.fill(left, ry, left + 2, ry + rh - 1, sideColor);
		String tag = e.inFight() ? "fighting" : e.entityId() >= 0 ? "placed" : "not placed";
		int tagW = ui.tw(tag);
		g.drawText(ui.font(), ui.font().trimToWidth(e.name(), Math.max(10, listW - indent - tagW - 14)), left + 6, ry + 3, 0xFFE6E8EB, false);
		g.drawText(ui.font(), tag, listX + listW - tagW - 4, ry + 3, e.inFight() ? ui.gold : ui.dim, false);
		float frac = e.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, e.hp() / e.max()));
		int barW = Math.max(10, Math.min(70, listW - indent - 50));
		g.fill(left + 6, ry + 15, left + 6 + barW, ry + 18, 0xFF05060A);
		g.fill(left + 6, ry + 15, left + 6 + Math.round(barW * frac), ry + 18, sideColor);
		g.drawText(ui.font(), Math.round(e.hp()) + "/" + Math.round(e.max()), left + 10 + barW, ry + 12, ui.muted, false);
	}

	// ------------------------------------------------------------------ drawing

	void draw(EncounterScreen ui, DrawContext g, int x, int y, int w, int h) {
		if (deleteArmed && System.currentTimeMillis() - deleteArmedAt > 3000) deleteArmed = false;
		boolean browsing = mode == BROWSE;
		DmStatePayload.ActorInfo a = browsing && !selectedId.isEmpty() ? selected() : null;
		if (a != null && !loadedFor.equals(a.id())) load(a);

		listX = x + 6;
		listW = MathHelper.clamp(w / 3, 100, 150);
		drawList(ui, g, x, y, h);

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
		if (mode == CREATE) cy = drawCreate(ui, g, areaX, cy, areaW);
		else if (mode == IMPORT) cy = drawImport(ui, g, areaX, cy, areaW);
		else if (a != null) cy = drawEdit(ui, g, a, areaX, cy, areaW);
		else if (!selectedFolder.isEmpty() && folderExists(selectedFolder)) cy = drawFolder(ui, g, areaX, cy, areaW);
		else cy = drawNothing(ui, g, areaX, cy, areaW);
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

	/** Tag chips, wrapped. {@code on} marks the ones that are switched on; a click runs {@code click} with the tag. */
	private int chips(EncounterScreen ui, DrawContext g, int x, int y, int w, List<String> tags, List<String> on, java.util.function.Consumer<String> click, String tip) {
		int cx = x;
		for (String t : tags) {
			String text = "#" + t;
			int bw = ui.tw(text) + 8;
			if (cx > x && cx + bw > x + w) {
				cx = x;
				y += 15;
			}
			boolean active = on != null && hasTag(on, t);
			final String tag = t;
			btn(ui, g, cx, y, text, () -> click.accept(tag), tip, active ? 0xFF6B4E12 : ui.panel, active ? ui.gold : 0xFF3B414C);
			cx += bw + 3;
		}
		return y + 15;
	}

	private int drawNothing(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		g.drawText(ui.font(), "Pick an Actor or a folder on the left, or make one.", x, y + 2, ui.dim, false);
		y += 16;
		List<String> tags = allTags();
		if (!tags.isEmpty()) {
			g.drawText(ui.font(), "Tags in use (click to find them)", x, y + 2, ui.muted, false);
			y += 13;
			y = chips(ui, g, x, y, w, tags, null, t -> {
				search.text = "#" + t;
				listScroll = 0;
			}, "Show only the Actors with this tag");
			y += 4;
		}
		return hints(ui, g, x, y, w,
				"Search finds by name, folder or #tag. Folders keep a scene's creatures together; Import turns sheets you already have into Actors.");
	}

	private int folderRow(EncounterScreen ui, DrawContext g, int x, int y, int w, String text) {
		int cx = x + 44;
		label(ui, g, "Folder", x, y);
		plain(ui, g, cx, y, ui.font().trimToWidth(folderLabel(folderPick), Math.max(30, w - 52)), () -> folderPick = nextFolder(folderPick),
				"Click for the next folder");
		return y + 20;
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
		y += 20;

		y = folderRow(ui, g, x, y, w, "");
		label(ui, g, "Tags", x, y);
		input(ui, g, bulkTags, cx, y, Math.max(40, cw));
		y += 24;

		gold(ui, g, x, y, "Create", () -> {
			if (sheets.isEmpty()) return;
			send(new ActorActionPayload(ActorActionPayload.CREATE, folderPick, nameInput.text.trim(), sheets.get(sheetIdx).link,
					modelText(kindIdx, valueInput.text), (linked ? 1 : 0) | (side << 1), bulkTags.text.trim()));
			if (!folderPick.isEmpty()) collapsed.remove(folderPick);
			mode = BROWSE;
			selectedId = "";
			loadedFor = "";
			unfocus();
		}, sheets.isEmpty() ? "No sheet to give it yet" : "Make the Actor (then place it)");
		plain(ui, g, x + 48, y, "Cancel", () -> mode = BROWSE, null);
		y += 20;
		return hints(ui, g, x, y, w, "Tip: Own copy for monsters, Shared for named characters.");
	}

	// ------------------------------------------------------------------ import

	private static boolean sheetMatches(CharacterData c, String q) {
		if (q.isEmpty()) return true;
		String s = q.toLowerCase(Locale.ROOT);
		return c.displayName().toLowerCase(Locale.ROOT).contains(s) || c.kind.toLowerCase(Locale.ROOT).contains(s)
				|| c.ownerName.toLowerCase(Locale.ROOT).contains(s);
	}

	private int drawImport(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		int cx = x + 44;
		int cw = w - 44;
		g.drawText(ui.font(), "Import sheets as Actors", x, y + 2, ui.gold, false);
		y += 16;
		int bx = x;
		bx += plain(ui, g, bx, y, linked ? "Shared" : "Own copy", () -> linked = !linked,
				"Own copy: each Actor gets its own hit points (use this for monsters). Shared: the Actor uses the sheet itself.") + 4;
		bx += plain(ui, g, bx, y, SIDES[side], () -> side = (side + 1) % 3, "The side every imported Actor takes") + 4;
		y += 18;
		y = folderRow(ui, g, x, y, w, "");
		label(ui, g, "Tags", x, y);
		input(ui, g, bulkTags, cx, y, Math.max(40, cw));
		y += 20;
		label(ui, g, "Find", x, y);
		input(ui, g, importFilter, cx, y, Math.max(40, cw));
		y += 20;

		List<CharacterData> shown = new ArrayList<>();
		for (CharacterData c : sheets()) if (sheetMatches(c, importFilter.text.trim())) shown.add(c);
		picked.removeIf(id -> sheets().stream().noneMatch(c -> c.link.equals(id)));
		if (shown.isEmpty()) {
			g.drawText(ui.font(), sheets().isEmpty() ? "No sheets yet: link a character to the server first." : "No sheet matches.", x, y + 2, ui.dim, false);
			y += 14;
		}
		for (CharacterData c : shown) {
			boolean on = picked.contains(c.link);
			if (y + 12 > clipTop && y < clipBottom) {
				final String id = c.link;
				boolean over = ui.hit(x, y, w, 12, () -> {
					if (!picked.remove(id)) picked.add(id);
				}, "Click to pick or drop it");
				if (over) g.fill(x, y, x + w, y + 12, 0xFF1A1F29);
				g.fill(x, y + 1, x + 10, y + 11, 0xFF3B414C);
				g.fill(x + 1, y + 2, x + 9, y + 10, on ? ui.gold : 0xFF0F1117);
				String who = c.remote ? c.ownerName : "yours";
				String note = c.kind + ", " + who;
				int nw = ui.tw(note);
				g.drawText(ui.font(), ui.font().trimToWidth(c.displayName(), Math.max(20, w - nw - 22)), x + 14, y + 2, 0xFFE6E8EB, false);
				g.drawText(ui.font(), note, x + w - nw - 2, y + 2, ui.dim, false);
			}
			y += 12;
		}
		y += 6;
		bx = x;
		bx += plain(ui, g, bx, y, "All shown", () -> {
			for (CharacterData c : shown) picked.add(c.link);
		}, "Pick every sheet in the list") + 4;
		bx += plain(ui, g, bx, y, "None", picked::clear, "Drop every pick") + 4;
		final int count = picked.size();
		gold(ui, g, bx, y, count == 0 ? "Import" : "Import " + count, () -> doImport(shown), count == 0 ? "Pick some sheets first" : "Make an Actor out of each picked sheet");
		bx = x;
		y += 16;
		plain(ui, g, bx, y, "Cancel", () -> mode = BROWSE, null);
		y += 20;
		return hints(ui, g, x, y, w,
				"Each Actor is named after its sheet and looks like the creature of that name when Minecraft has one (else a villager); change the look afterwards.");
	}

	private void doImport(List<CharacterData> shown) {
		int sent = 0;
		for (CharacterData c : sheets()) {
			if (!picked.contains(c.link)) continue;
			send(new ActorActionPayload(ActorActionPayload.IMPORT, folderPick, "", c.link, guessModel(c), (linked ? 1 : 0) | (side << 1), bulkTags.text.trim()));
			sent++;
		}
		if (sent == 0) return;
		if (!folderPick.isEmpty()) {
			collapsed.remove(folderPick);
			selectedFolder = folderPick;
			loadedFolder = "";
		}
		picked.clear();
		mode = BROWSE;
		selectedId = "";
		unfocus();
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

	// ------------------------------------------------------------------ folder

	private int drawFolder(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		final String name = selectedFolder;
		if (!loadedFolder.equals(name)) {
			loadedFolder = name;
			folderInput.text = name;
			deleteArmed = false;
			formScroll = 0;
		}
		int cx = x + 44;
		int cw = w - 44;
		int count = 0;
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.folder().equalsIgnoreCase(name)) count++;
		g.drawText(ui.font(), ui.font().trimToWidth("Folder  -  " + name + "  (" + count + ")", w), x, y + 2, ui.gold, false);
		y += 16;

		label(ui, g, "Name", x, y);
		int renameW = ui.tw("Rename") + 8;
		input(ui, g, folderInput, cx, y, Math.max(40, cw - renameW - 4));
		gold(ui, g, cx + Math.max(40, cw - renameW - 4) + 4, y, "Rename", () -> {
			send(new ActorActionPayload(ActorActionPayload.FOLDER_RENAME, "", name, folderInput.text.trim(), "", 0));
			String to = folderInput.text.trim();
			if (!to.isEmpty()) {
				selectedFolder = to;
				loadedFolder = to;
				if (collapsed.remove(name)) collapsed.add(to);
			}
		}, "Rename the folder");
		y += 22;

		btn(ui, g, x, y, deleteArmed ? "Really delete?" : "Delete folder", () -> {
			if (!deleteArmed) {
				deleteArmed = true;
				deleteArmedAt = System.currentTimeMillis();
				return;
			}
			deleteArmed = false;
			send(new ActorActionPayload(ActorActionPayload.FOLDER_DELETE, "", name, "", "", 0));
			selectedFolder = "";
			loadedFolder = "";
		}, "Click twice. The Actors inside are kept, they just leave the folder.", 0xFF7A1C27, 0xFFB8323F);
		y += 24;
		return hints(ui, g, x, y, w, "A folder only groups Actors in the list; deleting it never deletes an Actor.",
				"To file an Actor, pick it and use its Folder button.");
	}

	// ------------------------------------------------------------------ one Actor

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
		y += 20;

		label(ui, g, "Folder", x, y);
		plain(ui, g, cx, y, ui.font().trimToWidth(folderLabel(a.folder()), Math.max(30, cw - 8)), () ->
				send(new ActorActionPayload(ActorActionPayload.FOLDER, id, nextFolder(a.folder()), "", "", 0)),
				"Click to move it to the next folder (make folders with + Folder)");
		y += 20;

		// tags: every tag in use as a switch, then a box for a new one
		List<String> mine = tagsOf(a);
		List<String> shown = allTags();
		for (String t : mine) if (!hasTag(shown, t)) shown.add(t);
		label(ui, g, "Tags", x, y);
		int tagW = ui.tw("Add") + 8;
		input(ui, g, tagInput, cx, y, Math.max(40, cw - tagW - 4));
		gold(ui, g, cx + Math.max(40, cw - tagW - 4) + 4, y, "Add", () -> {
			List<String> next = new ArrayList<>(mine);
			for (String t : tagInput.text.split(",")) {
				String clean = t.trim();
				while (clean.startsWith("#")) clean = clean.substring(1).trim();
				if (!clean.isEmpty() && !hasTag(next, clean)) next.add(clean);
			}
			tagInput.text = "";
			send(new ActorActionPayload(ActorActionPayload.TAGS, id, String.join(",", next), "", "", 0));
		}, "Give the Actor the tag you typed");
		y += 18;
		if (!shown.isEmpty()) {
			y = chips(ui, g, x, y, w, shown, mine, t -> {
				List<String> next = new ArrayList<>(mine);
				if (hasTag(next, t)) next.removeIf(x2 -> x2.equalsIgnoreCase(t));
				else next.add(t);
				send(new ActorActionPayload(ActorActionPayload.TAGS, id, String.join(",", next), "", "", 0));
			}, "Click to give or take away this tag");
		}
		y += 6;

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
		for (TextInput t : inputs) if (t.focused) return true;
		return false;
	}

	boolean charTyped(char c) {
		for (TextInput t : inputs) if (t.charTyped(c)) return true;
		return false;
	}

	boolean keyPressed(int keyCode, int modifiers) {
		for (TextInput t : inputs) if (t.keyPressed(keyCode, modifiers)) return true;
		return false;
	}

	/** A click that landed outside any text box takes the focus away (called after the hits ran). */
	void blur() {
		unfocus();
	}

	boolean scrolled(double mx, double my, double vertical) {
		if (mx >= listX && mx < listX + listW) {
			listScroll = Math.max(0, listScroll - (int) Math.signum(vertical) * ROW_H);
			return true;
		}
		formScroll = Math.max(0, formScroll - (int) Math.signum(vertical) * 12);
		return true;
	}
}
