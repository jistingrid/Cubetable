package dev.tacticalcombat.client;

import com.google.gson.JsonObject;
import dev.tacticalcombat.character.Gz;
import dev.tacticalcombat.codex.CodexEntry;
import dev.tacticalcombat.codex.CodexLibrary;
import dev.tacticalcombat.net.ActorActionPayload;
import dev.tacticalcombat.net.CodexActionPayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CodexRules;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This Dungeon Master's codex: what the game packs on this computer hold (read from disk) and what has been put into the
 * game (the server's copy, which arrives like any other sheet). Entries are plain files, so the list is read again
 * whenever the window asks; nothing is sent to the server until the Dungeon Master adds an entry.
 */
final class CodexClient {
	/** The largest entry the server takes (the packet limit minus a margin). */
	private static final int MAX_PACKET = 29000;

	static List<CodexEntry> entries = List.of();
	static List<String> problems = List.of();
	private static boolean scanned;

	private CodexClient() {}

	static void clear() {
		entries = List.of();
		problems = List.of();
		scanned = false;
		CHECKED.clear();
	}

	/** Reads the codex folders of every pack again (a few small files; done when the tab opens and on Rescan). */
	static void rescan() {
		if (SheetLibrary.FORMATS.isEmpty()) SheetLibrary.reloadPacks(); // formats only: characters are left alone
		CodexLibrary.Result r = CodexLibrary.scan(SheetLibrary.packsDir(), SheetLibrary.FORMATS);
		entries = r.entries();
		problems = r.problems();
		scanned = true;
		CHECKED.clear();
	}

	static void ensureScanned() {
		if (!scanned) rescan();
	}

	/** Games that can have a codex: every installed format, those with entries first. */
	static List<SheetFormat> games() {
		List<SheetFormat> out = new ArrayList<>();
		for (SheetFormat f : SheetLibrary.FORMATS.values()) if (hasEntries(f.id)) out.add(f);
		for (SheetFormat f : SheetLibrary.FORMATS.values()) if (!out.contains(f)) out.add(f);
		return out;
	}

	static boolean hasEntries(String formatId) {
		for (CodexEntry e : entries) if (e.formatId.equals(formatId)) return true;
		return false;
	}

	static List<CodexEntry> of(String formatId, String type) {
		List<CodexEntry> out = new ArrayList<>();
		for (CodexEntry e : entries) if (e.formatId.equals(formatId) && e.type.equals(type)) out.add(e);
		out.sort((a, b) -> {
			int f = a.folder.compareToIgnoreCase(b.folder);
			return f != 0 ? f : a.name.compareToIgnoreCase(b.name);
		});
		return out;
	}

	// ------------------------------------------------------------------ what is in the game

	/** The server's copy of this entry, or null while it is not in the game. */
	static CharacterData inGame(CodexEntry e) {
		CharacterData c = ServerCharacters.byId(e.gameId());
		return c != null && ServerCharacters.isCodexSheet(c) ? c : null;
	}

	/** True when the game has this entry but the file has changed since (adding it again updates it). */
	static boolean outdated(CodexEntry e) {
		CharacterData have = inGame(e);
		if (have == null) return false;
		Checked known = CHECKED.get(e.key());
		if (known != null && known.version == have.linkVersion) return known.outdated; // the window asks every frame
		boolean differs;
		try {
			JsonObject mine = CharacterData.parse(e.sheet.deepCopy(), "").toJson();
			JsonObject theirs = have.toJson();
			theirs.remove("link");
			theirs.remove("linkVersion");
			differs = !mine.equals(theirs);
		} catch (RuntimeException ex) {
			differs = false;
		}
		CHECKED.put(e.key(), new Checked(have.linkVersion, differs));
		return differs;
	}

	/** What {@link #outdated} found for the server's copy of this version (cleared when the files are read again). */
	private record Checked(long version, boolean outdated) {}

	private static final Map<String, Checked> CHECKED = new HashMap<>();

	// ------------------------------------------------------------------ doing things

	private static boolean ready() {
		if (!ServerCharacters.isDm()) {
			say("Only a Dungeon Master can add to the game.", Formatting.RED);
			return false;
		}
		if (!ClientPlayNetworking.canSend(CodexActionPayload.ID)) {
			say("This server does not have the codex (it needs the same version of Tactical Combat).", Formatting.RED);
			return false;
		}
		return true;
	}

	/** Sends the entry to the server, which keeps it as a template for Actors. Returns false when nothing was sent. */
	static boolean add(CodexEntry e) {
		if (!ready()) return false;
		byte[] data = Gz.pack(e.sheet.toString());
		if (data.length > MAX_PACKET) {
			say(e.name + " is too big to send (over about 29 KB compressed).", Formatting.RED);
			return false;
		}
		ClientPlayNetworking.send(new CodexActionPayload(CodexActionPayload.ADD, e.key(), data));
		return true;
	}

	static void remove(CodexEntry e) {
		if (ready()) ClientPlayNetworking.send(new CodexActionPayload(CodexActionPayload.REMOVE, e.key(), new byte[0]));
	}

	/**
	 * Makes {@code count} Actors out of the entry, each with its own copy of the sheet (hit points of their own).
	 * The entry is added to the game first when it is not there or has changed; the server handles the packets in the
	 * order they are sent, so the sheet is there when the Actors are made. {@code side} -1 = the entry's, else the game's default (hostile).
	 */
	static boolean makeActors(CodexEntry e, int count, int side) {
		if (!ready()) return false;
		if (inGame(e) == null || outdated(e)) {
			if (!add(e)) return false;
		}
		int s = side >= 0 ? side : e.side >= 0 ? e.side : CodexEntry.HOSTILE;
		String model = e.model.isEmpty() ? ActorsPanel.guessModel(e.name) : e.model;
		StringBuilder tagText = new StringBuilder();
		for (String t : e.tags) { // the packet holds 128 characters
			if (tagText.length() + t.length() + 1 > 120) break;
			if (tagText.length() > 0) tagText.append(',');
			tagText.append(t);
		}
		String tags = tagText.toString();
		for (int i = 0; i < Math.max(1, Math.min(20, count)); i++) {
			ClientPlayNetworking.send(new ActorActionPayload(ActorActionPayload.IMPORT, "", "", e.gameId(), model, s << 1, tags));
		}
		return true;
	}

	/** The folder on disk where new entries of a game's type go, or null when no pack folder holds that game. */
	static Path folderFor(String formatId, String type) {
		String pack = CodexLibrary.packFolderFor(SheetLibrary.packsDir(), formatId);
		return pack.isEmpty() ? null : CodexLibrary.folderOf(SheetLibrary.packsDir(), pack, type);
	}

	static String typeLabel(SheetFormat f, String type) {
		return f == null ? CodexRules.defaults(type).label : f.codexType(type).label;
	}

	static void say(String text, Formatting color) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(Text.literal("[Codex] " + text).formatted(color), false);
	}
}
