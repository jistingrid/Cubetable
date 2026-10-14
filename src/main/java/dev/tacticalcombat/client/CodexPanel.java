package dev.tacticalcombat.client;

import dev.tacticalcombat.codex.CodexEntry;
import dev.tacticalcombat.codex.CodexLibrary;
import dev.tacticalcombat.sheet.CodexRules;
import dev.tacticalcombat.sheet.SheetFormat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Codex tab of the DM window: the ready-made monsters (later weapons, features, spells) that the game packs on this
 * computer carry. The list is on the left (searchable, in the folders the files sit in, with the figures the pack chose to
 * show), the chosen entry on the right with what it can do and the buttons that put it into the game. Entries stay out of
 * everybody's sheet list until added; "Add as Actor" does the whole job in one click. Files can be dropped onto the tab.
 * What is called what, and which numbers are shown, comes from the game's pack ({@link CodexRules}), not from here.
 */
final class CodexPanel {
	private static final int ROW_H = 24;
	private static final int FOLDER_H = 14;
	private static final String TYPE = CodexRules.MONSTERS;
	private static final String[] SIDE_LABEL = {"Hostile", "Neutral", "Friendly"};
	private static final int GREEN = 0xFF3CB84A;
	private static final int RED_TEXT = 0xFFE07070;

	private static String gameId = "";
	private static String selectedKey = "";
	private static final Set<String> collapsed = new HashSet<>();
	private static int count = 1;
	/** -1 = the side the entry names (else hostile), 0..2 = a side chosen here. */
	private static int side = -1;

	private final Set<String> picked = new LinkedHashSet<>();
	private final TextInput search = new TextInput("Search name, folder or #tag", 40);
	private final TextInput[] inputs = {search};
	private int listScroll;
	private int formScroll;
	private int formHeight;
	private boolean removeArmed;
	private long removeArmedAt;
	private String note = "";
	private final List<String> noteLines = new ArrayList<>();
	private long noteAt;

	private int clipTop, clipBottom;
	private int areaX, areaY, areaW, areaH;
	private int listX, listY, listW, listH;

	/** One line of the left list: a folder header, an "Unfiled" label or an entry. */
	private record Row(String folder, CodexEntry entry, int count, boolean label) {}

	// ------------------------------------------------------------------ data

	void onOpen() {
		CodexClient.rescan();
		removeArmed = false;
	}

	private SheetFormat format() {
		return dev.tacticalcombat.sheet.SheetLibrary.FORMATS.get(gameId);
	}

	private void pickGame(List<SheetFormat> games) {
		boolean known = false;
		for (SheetFormat f : games) if (f.id.equals(gameId)) known = true;
		if (!known) gameId = games.isEmpty() ? "" : games.get(0).id;
	}

	private List<CodexEntry> all() {
		return CodexClient.of(gameId, TYPE);
	}

	private CodexEntry selected() {
		for (CodexEntry e : all()) if (e.key().equals(selectedKey)) return e;
		return null;
	}

	private static boolean matches(CodexEntry e, String query) {
		if (query.isEmpty()) return true;
		String q = query.toLowerCase(Locale.ROOT);
		if (q.startsWith("#")) {
			String want = q.substring(1).trim();
			if (want.isEmpty()) return !e.tags.isEmpty();
			for (String t : e.tags) if (t.toLowerCase(Locale.ROOT).contains(want)) return true;
			return false;
		}
		return e.haystack().contains(q);
	}

	private List<CodexEntry> shown() {
		List<CodexEntry> out = new ArrayList<>();
		String q = search.text.trim();
		for (CodexEntry e : all()) if (matches(e, q)) out.add(e);
		return out;
	}

	private static String statsLine(CodexEntry e) {
		StringBuilder sb = new StringBuilder();
		for (CodexEntry.Stat s : e.stats) {
			if (sb.length() > 0) sb.append("  ");
			sb.append(s.label()).append(' ').append(s.text().isEmpty() ? "-" : s.text());
		}
		return sb.toString();
	}

	private static List<String> allTags(List<CodexEntry> entries) {
		Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (CodexEntry e : entries) seen.addAll(e.tags);
		return new ArrayList<>(seen);
	}

	private void tell(String headline, List<String> lines) {
		note = headline;
		noteLines.clear();
		noteLines.addAll(lines);
		noteAt = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ drawing helpers

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

	private int hints(EncounterScreen ui, DrawContext g, int x, int y, int w, int color, String... lines) {
		for (String line : lines) {
			for (OrderedText l : ui.font().wrapLines(Text.literal(line), w)) {
				if (y + 9 > clipTop && y < clipBottom) g.drawText(ui.font(), l, x, y, color, false);
				y += 10;
			}
			y += 2;
		}
		return y;
	}

	private boolean listHit(EncounterScreen ui, int x, int y, int w, int h, Runnable action, String tip) {
		int y0 = Math.max(y, listY);
		int y1 = Math.min(y + h, listY + listH);
		return y1 > y0 && ui.hit(x, y0, w, y1 - y0, action, tip);
	}

	// ------------------------------------------------------------------ the list (left)

	private List<Row> rows(List<CodexEntry> entries) {
		List<Row> out = new ArrayList<>();
		if (!search.text.isBlank()) { // a search is a flat list of what matches
			for (CodexEntry e : entries) out.add(new Row("", e, 0, false));
			return out;
		}
		Set<String> folders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (CodexEntry e : entries) if (!e.folder.isEmpty()) folders.add(e.folder);
		for (String f : folders) {
			int n = 0;
			for (CodexEntry e : entries) if (e.folder.equalsIgnoreCase(f)) n++;
			out.add(new Row(f, null, n, false));
			if (collapsed.contains(f)) continue;
			for (CodexEntry e : entries) if (e.folder.equalsIgnoreCase(f)) out.add(new Row(f, e, 0, false));
		}
		boolean header = !folders.isEmpty();
		for (CodexEntry e : entries) {
			if (!e.folder.isEmpty()) continue;
			if (header) {
				out.add(new Row("", null, 0, true));
				header = false;
			}
			out.add(new Row("", e, 0, false));
		}
		return out;
	}

	private static int rowHeight(Row r) {
		return r.entry() != null ? ROW_H : FOLDER_H;
	}

	private void drawList(EncounterScreen ui, DrawContext g, int x, int y, int h, List<SheetFormat> games) {
		int sy = y + 6;
		// the game
		SheetFormat f = format();
		String gameText = f == null ? "No game packs" : f.name + " - " + CodexClient.typeLabel(f, TYPE);
		final List<SheetFormat> options = games;
		g.fill(listX, sy, listX + listW, sy + 14, 0xFF3B414C);
		boolean over = ui.hit(listX, sy, listW, 14, () -> {
			if (options.size() < 2) return;
			int at = 0;
			for (int i = 0; i < options.size(); i++) if (options.get(i).id.equals(gameId)) at = i;
			gameId = options.get((at + 1) % options.size()).id;
			selectedKey = "";
			picked.clear();
			listScroll = 0;
			formScroll = 0;
		}, options.size() < 2 ? "The game these entries are for" : "Click for the next game");
		g.fill(listX + 1, sy + 1, listX + listW - 1, sy + 13, over && options.size() > 1 ? ui.panelHover : ui.panel);
		String arrow = options.size() > 1 ? " >" : "";
		g.drawText(ui.font(), ui.font().trimToWidth(gameText, listW - 6 - ui.tw(arrow)) + arrow, listX + 3, sy + 3, 0xFFFFFFFF, false);

		int ty = sy + 18;
		search.draw(g, ui.font(), listX, ty, listW, 14, 0xFF3B414C, 0xFF0F1117);
		ui.hit(listX, ty, listW, 14, () -> search.focused = true, "Type a name or folder, or #tag to find by tag");

		listY = ty + 18;
		listH = y + h - 38 - listY;
		g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, ui.edge);
		g.fill(listX, listY, listX + listW, listY + listH, 0xFF0B0C10);

		List<CodexEntry> entries = shown();
		List<Row> rows = rows(entries);
		int total = 0;
		for (Row r : rows) total += rowHeight(r);
		listScroll = MathHelper.clamp(listScroll, 0, Math.max(0, total - listH));
		g.enableScissor(listX, listY, listX + listW, listY + listH);
		if (rows.isEmpty()) {
			boolean none = all().isEmpty();
			g.drawText(ui.font(), none ? "Nothing here yet." : "Nothing found.", listX + 6, listY + 6, ui.dim, false);
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
		bx += btn(ui, g, bx, by, "All shown", () -> {
			for (CodexEntry e : entries) picked.add(e.key());
		}, "Tick every entry in the list", ui.panel, 0xFF3B414C) + 4;
		btn(ui, g, bx, by, "None", picked::clear, "Untick everything", ui.panel, 0xFF3B414C);
		bx = listX;
		by += 15;
		bx += btn(ui, g, bx, by, "Rescan", this::onOpen, "Read the codex files from disk again", ui.panel, 0xFF3B414C) + 4;
		btn(ui, g, bx, by, "Open folder", this::openFolder, "Open the folder the entries are kept in (drop files onto this tab to add them)", ui.panel, 0xFF3B414C);
	}

	private void drawRow(EncounterScreen ui, DrawContext g, Row r, int ry, int rh) {
		if (r.entry() == null) {
			if (r.label()) {
				g.drawText(ui.font(), "No folder", listX + 6, ry + 3, ui.dim, false);
				return;
			}
			boolean closed = collapsed.contains(r.folder());
			final String folder = r.folder();
			boolean over = listHit(ui, listX, ry, listW, rh, () -> {
				if (!collapsed.remove(folder)) collapsed.add(folder);
			}, closed ? "Open the folder" : "Close the folder");
			g.fill(listX, ry, listX + listW, ry + rh - 1, over ? ui.panel : 0xFF161A22);
			g.drawText(ui.font(), closed ? "+" : "-", listX + 4, ry + 3, ui.gold, false);
			g.drawText(ui.font(), ui.font().trimToWidth(folder + " (" + r.count() + ")", listW - 20), listX + 14, ry + 3, ui.gold, false);
			return;
		}
		CodexEntry e = r.entry();
		int indent = !search.text.isBlank() || r.folder().isEmpty() ? 0 : 6;
		int left = listX + indent;
		boolean sel = e.key().equals(selectedKey);
		final String key = e.key();
		boolean over = listHit(ui, left + 12, ry, listW - indent - 12, rh - 1, () -> {
			selectedKey = key;
			formScroll = 0;
			removeArmed = false;
			search.focused = false;
		}, null);
		g.fill(left, ry, listX + listW, ry + rh - 1, sel ? ui.panelHover : over ? ui.panel : 0xFF11131A);

		// tick box
		boolean on = picked.contains(key);
		listHit(ui, left, ry, 13, rh - 1, () -> {
			if (!picked.remove(key)) picked.add(key);
		}, "Tick to add several at once");
		g.fill(left + 3, ry + 3, left + 12, ry + 12, 0xFF3B414C);
		g.fill(left + 4, ry + 4, left + 11, ry + 11, on ? ui.gold : 0xFF0F1117);

		CharacterStateMark mark = markOf(e);
		int markW = mark == null ? 0 : ui.tw(mark.text) + 4;
		g.drawText(ui.font(), ui.font().trimToWidth(e.name, Math.max(10, listW - indent - markW - 22)), left + 16, ry + 3, 0xFFE6E8EB, false);
		if (mark != null) g.drawText(ui.font(), mark.text, listX + listW - markW, ry + 3, mark.color, false);
		g.drawText(ui.font(), ui.font().trimToWidth(statsLine(e), listW - indent - 20), left + 16, ry + 13, ui.dim, false);
	}

	private record CharacterStateMark(String text, int color) {}

	/** "in game" when the server has it, "update" when the file has changed since, else nothing. */
	private static CharacterStateMark markOf(CodexEntry e) {
		if (CodexClient.inGame(e) == null) return null;
		return CodexClient.outdated(e) ? new CharacterStateMark("update", 0xFFE0B84C) : new CharacterStateMark("in game", GREEN);
	}

	// ------------------------------------------------------------------ drawing

	void draw(EncounterScreen ui, DrawContext g, int x, int y, int w, int h) {
		CodexClient.ensureScanned();
		if (removeArmed && System.currentTimeMillis() - removeArmedAt > 3000) removeArmed = false;
		if (!note.isEmpty() && System.currentTimeMillis() - noteAt > 12000) {
			note = "";
			noteLines.clear();
		}
		List<SheetFormat> games = CodexClient.games();
		pickGame(games);
		picked.removeIf(k -> {
			for (CodexEntry e : all()) if (e.key().equals(k)) return false;
			return true;
		});

		listX = x + 6;
		listW = MathHelper.clamp(w * 9 / 20, 130, 210);
		drawList(ui, g, x, y, h, games);

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
		cy = drawNote(ui, g, areaX, cy, areaW);
		CodexEntry e = selected();
		if (!picked.isEmpty()) cy = drawBulk(ui, g, areaX, cy, areaW);
		if (e != null) cy = drawEntry(ui, g, e, areaX, cy, areaW);
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

	private int drawNote(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		if (note.isEmpty()) return y;
		g.drawText(ui.font(), ui.font().trimToWidth(note, w), x, y + 2, ui.gold, false);
		y += 13;
		int shownLines = 0;
		for (String line : noteLines) {
			if (shownLines++ >= 5) {
				g.drawText(ui.font(), "... and " + (noteLines.size() - 5) + " more", x, y, RED_TEXT, false);
				y += 11;
				break;
			}
			y = hints(ui, g, x, y, w, RED_TEXT, line);
		}
		return y + 4;
	}

	private int drawNothing(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		List<CodexEntry> entries = all();
		SheetFormat f = format();
		if (f == null) {
			return hints(ui, g, x, y, w, ui.dim, "No game packs are installed on this computer, so there is no codex to show. Packs live in config/tacticalcombat/packs.");
		}
		String what = CodexClient.typeLabel(f, TYPE);
		if (entries.isEmpty()) {
			g.drawText(ui.font(), what + " is empty", x, y + 2, ui.gold, false);
			y += 16;
			Path folder = CodexClient.folderFor(f.id, TYPE);
			y = hints(ui, g, x, y, w, ui.dim,
					"Drop .json files onto this window to add monsters, or copy them into the folder below.",
					folder == null ? "This game's pack is not in your packs folder, so there is nowhere to keep entries for it."
							: "Folder: " + folder);
		} else {
			g.drawText(ui.font(), what + " - " + entries.size() + (entries.size() == 1 ? " entry" : " entries") + ", " + inGameCount(entries) + " in the game", x, y + 2, ui.gold, false);
			y += 16;
			y = hints(ui, g, x, y, w, ui.dim,
					"Pick an entry on the left to read it. Add as Actor puts it in the game and makes an Actor in one click; tick several to add them together.",
					"Entries stay out of everyone's sheet list until you add them. Drop .json files onto this window to add more.");
			List<String> tags = allTags(entries);
			if (!tags.isEmpty()) {
				g.drawText(ui.font(), "Tags (click to find them)", x, y + 2, ui.muted, false);
				y += 13;
				y = tagChips(ui, g, x, y, w, tags);
			}
		}
		return drawProblems(ui, g, x, y + 4, w);
	}

	private static int inGameCount(List<CodexEntry> entries) {
		int n = 0;
		for (CodexEntry e : entries) if (CodexClient.inGame(e) != null) n++;
		return n;
	}

	private int tagChips(EncounterScreen ui, DrawContext g, int x, int y, int w, List<String> tags) {
		int cx = x;
		for (String t : tags) {
			String text = "#" + t;
			int bw = ui.tw(text) + 8;
			if (cx > x && cx + bw > x + w) {
				cx = x;
				y += 15;
			}
			final String tag = t;
			btn(ui, g, cx, y, text, () -> {
				search.text = "#" + tag;
				listScroll = 0;
			}, "Show only the entries with this tag", ui.panel, 0xFF3B414C);
			cx += bw + 3;
		}
		return y + 15;
	}

	private int drawProblems(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		if (CodexClient.problems.isEmpty()) return y;
		g.drawText(ui.font(), CodexClient.problems.size() + (CodexClient.problems.size() == 1 ? " file has a problem" : " files have problems"), x, y + 2, RED_TEXT, false);
		y += 13;
		int n = 0;
		for (String p : CodexClient.problems) {
			if (n++ >= 6) {
				g.drawText(ui.font(), "... and " + (CodexClient.problems.size() - 6) + " more (see the log)", x, y, RED_TEXT, false);
				y += 11;
				break;
			}
			y = hints(ui, g, x, y, w, RED_TEXT, p);
		}
		return y;
	}

	private int sideButton(EncounterScreen ui, DrawContext g, int x, int y, CodexEntry entryOrNull) {
		String own = entryOrNull != null && entryOrNull.side >= 0 ? SIDE_LABEL[entryOrNull.side] : SIDE_LABEL[CodexEntry.HOSTILE];
		String label = side < 0 ? "Entry's (" + own + ")" : SIDE_LABEL[side];
		return plain(ui, g, x, y, label, () -> side = side >= 2 ? -1 : side + 1,
				"The side the Actors take: the entry's own (hostile when it names none), or one you choose for all of them");
	}

	private int countRow(EncounterScreen ui, DrawContext g, int x, int y) {
		int bx = x;
		bx += plain(ui, g, bx, y, "-", () -> count = Math.max(1, count - 1), "One fewer Actor") + 2;
		String text = count + (count == 1 ? " Actor" : " Actors");
		g.drawText(ui.font(), text, bx + 2, y + 2, 0xFFE6E8EB, false);
		bx += ui.tw(text) + 8;
		plain(ui, g, bx, y, "+", () -> count = Math.min(20, count + 1), "One more Actor (each gets its own hit points, named Wolf 2, Wolf 3 ...)");
		return y + 18;
	}

	/** Actions on the ticked entries. */
	private int drawBulk(EncounterScreen ui, DrawContext g, int x, int y, int w) {
		List<CodexEntry> chosen = new ArrayList<>();
		for (CodexEntry e : all()) if (picked.contains(e.key())) chosen.add(e);
		g.drawText(ui.font(), chosen.size() + " ticked", x, y + 2, ui.gold, false);
		y += 15;
		y = countRow(ui, g, x, y);
		int bx = x;
		sideButton(ui, g, bx, y, null);
		y += 18;
		bx = x;
		bx += gold(ui, g, bx, y, "Add " + chosen.size() + " as Actors", () -> {
			int sent = 0;
			for (CodexEntry e : chosen) if (CodexClient.makeActors(e, count, side)) sent++;
			if (sent > 0) tell("Made " + (sent * count) + " Actors from " + sent + (sent == 1 ? " entry" : " entries") + ".", List.of());
			picked.clear();
		}, "Put them in the game and make Actors from them") + 4;
		plain(ui, g, bx, y, "Add to game", () -> {
			int sent = 0;
			for (CodexEntry e : chosen) if (CodexClient.add(e)) sent++;
			if (sent > 0) tell("Added " + sent + (sent == 1 ? " entry" : " entries") + " to the game.", List.of());
			picked.clear();
		}, "Put them in the game without making Actors (the Actors tab can then use them as sheets)");
		y += 20;
		g.fill(x, y, x + w, y + 1, ui.edge);
		return y + 8;
	}

	private int drawEntry(EncounterScreen ui, DrawContext g, CodexEntry e, int x, int y, int w) {
		g.drawText(ui.font(), ui.font().trimToWidth(e.name, w), x, y + 2, ui.gold, false);
		y += 14;
		if (!e.subtitle.isEmpty()) y = hints(ui, g, x, y, w, ui.muted, e.subtitle);

		// the figures the pack chose to show
		int cx = x;
		for (CodexEntry.Stat s : e.stats) {
			String label = s.label() + " ";
			String value = s.text().isEmpty() ? "-" : s.text();
			int cw = ui.tw(label) + ui.tw(value) + 10;
			if (cx > x && cx + cw > x + w) {
				cx = x;
				y += 14;
			}
			if (y + 12 > clipTop && y < clipBottom) {
				g.fill(cx, y, cx + cw, y + 12, 0xFF3B414C);
				g.fill(cx + 1, y + 1, cx + cw - 1, y + 11, 0xFF161A22);
				g.drawText(ui.font(), label, cx + 4, y + 2, ui.muted, false);
				g.drawText(ui.font(), value, cx + 4 + ui.tw(label), y + 2, 0xFFFFFFFF, false);
			}
			cx += cw + 3;
		}
		if (!e.stats.isEmpty()) y += 17;

		if (!e.tags.isEmpty()) y = tagChips(ui, g, x, y, w, e.tags);

		for (CodexEntry.Line line : e.preview) {
			if (y + 9 > clipTop && y < clipBottom) g.drawText(ui.font(), line.label(), x, y, ui.gold, false);
			y += 10;
			y = hints(ui, g, x, y, w, 0xFFC9CCD2, line.text());
		}
		y += 2;
		g.fill(x, y, x + w, y + 1, ui.edge);
		y += 7;

		// where it stands
		CharacterStateMark mark = markOf(e);
		String state = mark == null ? "Not in the game yet." : mark.text.equals("update") ? "In the game, but the file has changed since." : "In the game.";
		g.drawText(ui.font(), state, x, y + 2, mark == null ? ui.dim : mark.color, false);
		y += 15;

		y = countRow(ui, g, x, y);
		sideButton(ui, g, x, y, e);
		y += 20;

		int bx = x;
		bx += gold(ui, g, bx, y, count == 1 ? "Add as Actor" : "Add " + count + " as Actors", () -> {
			if (CodexClient.makeActors(e, count, side)) tell("Made " + count + (count == 1 ? " Actor" : " Actors") + " from " + e.name + ". Place them from the Actors tab.", List.of());
		}, "Put it in the game (if it is not there) and make an Actor with its own copy of the sheet") + 4;
		boolean update = mark != null && mark.text.equals("update");
		bx += plain(ui, g, bx, y, update ? "Update in game" : "Add to game", () -> {
			if (CodexClient.add(e)) tell((update ? "Updated " : "Added ") + e.name + (update ? " in the game." : " to the game."), List.of());
		}, update ? "Send the changed file to the game (Actors already made keep their own copy)"
				: "Put it in the game without making an Actor (the Actors tab can then pick it as a sheet)") + 4;
		y += 20;
		if (mark != null) {
			btn(ui, g, x, y, removeArmed ? "Really remove?" : "Remove from game", () -> {
				if (!removeArmed) {
					removeArmed = true;
					removeArmedAt = System.currentTimeMillis();
					return;
				}
				removeArmed = false;
				CodexClient.remove(e);
			}, "Click twice. Actors already made from it keep their own copy; one that shares it stops this.", 0xFF7A1C27, 0xFFB8323F);
			y += 20;
		}
		y = hints(ui, g, x, y + 2, w, ui.dim, "File: " + e.file);
		return drawProblems(ui, g, x, y + 4, w);
	}

	// ------------------------------------------------------------------ files

	private void openFolder() {
		SheetFormat f = format();
		if (f == null) return;
		Path folder = CodexClient.folderFor(f.id, TYPE);
		if (folder == null) {
			CodexClient.say("The pack for " + f.name + " is not in your packs folder, so there is no codex folder for it.", Formatting.RED);
			return;
		}
		try {
			Files.createDirectories(folder);
			Util.getOperatingSystem().open(folder.toFile());
		} catch (IOException ex) {
			CodexClient.say("Could not open " + folder + ": " + ex.getMessage(), Formatting.RED);
		}
	}

	/** Files dropped onto the window: copied into the game's codex folder, then read again. */
	void dropped(List<Path> paths) {
		SheetFormat f = format();
		String pack = f == null ? "" : CodexLibrary.packFolderFor(dev.tacticalcombat.sheet.SheetLibrary.packsDir(), f.id);
		CodexLibrary.Imported r = CodexLibrary.importFiles(dev.tacticalcombat.sheet.SheetLibrary.packsDir(), paths, pack, TYPE);
		CodexClient.rescan();
		String head = r.files() == 0 ? "Nothing was added." : "Added " + r.files() + (r.files() == 1 ? " file" : " files")
				+ " (" + r.entries() + (r.entries() == 1 ? " entry" : " entries") + ").";
		tell(head, r.errors());
		listScroll = 0;
		formScroll = 0;
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

	/** A click that landed outside any text box takes the focus away (called before the hits run). */
	void blur() {
		for (TextInput t : inputs) t.focused = false;
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
