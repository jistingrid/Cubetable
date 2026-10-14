package dev.tacticalcombat.codex;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * One entry of a pack's codex: a ready-made sheet (a monster) that lives in a file of the pack, not among the
 * player's own characters. Nothing of it is in the game until the Dungeon Master adds it.
 */
public final class CodexEntry {
	public static final int HOSTILE = 0, NEUTRAL = 1, FRIENDLY = 2;

	/** One figure the pack's codex columns show beside the name ("HP" "11"). */
	public record Stat(String label, String text) {}

	/** One line of the preview: a collection of the sheet and the names of its rows ("Weapons" "Bite"). */
	public record Line(String label, String text) {}

	/** Folder of the pack this came from. */
	public final String pack;
	public final String formatId;
	/** "monsters" ... (see {@link dev.tacticalcombat.sheet.CodexRules#TYPES}). */
	public final String type;
	/** Folder inside the type's folder ("" = none, "undead/lesser" ...); also the entry's own say, see {@code codex.folder}. */
	public final String folder;
	/** The file it was read from, relative to the type's folder (shown in messages). */
	public final String file;
	public final String name;
	public final String subtitle;
	/** What is sent to the server: the character JSON without the codex notes. */
	public final JsonObject sheet;
	/** "mob:minecraft:wolf" ...; empty = let the Actors tab guess from the name. */
	public final String model;
	/** {@link #HOSTILE} ... or -1 when the entry does not say. */
	public final int side;
	public final List<String> tags;
	/** What the pack's columns show, in the pack's order. */
	public final List<Stat> stats;
	public final List<Line> preview;

	public CodexEntry(String pack, String formatId, String type, String folder, String file, String name, String subtitle,
			JsonObject sheet, String model, int side, List<String> tags, List<Stat> stats, List<Line> preview) {
		this.pack = pack;
		this.formatId = formatId;
		this.type = type;
		this.folder = folder;
		this.file = file;
		this.name = name;
		this.subtitle = subtitle;
		this.sheet = sheet;
		this.model = model;
		this.side = side;
		this.tags = tags;
		this.stats = stats;
		this.preview = preview;
	}

	public String key() {
		return CodexKeys.key(formatId, type, name);
	}

	/** The id of this entry's sheet on the server once it is added. */
	public String gameId() {
		return CodexKeys.idOf(key());
	}

	/** Search text: name, subtitle, folder and tags, lower case. */
	public String haystack() {
		return (name + " " + subtitle + " " + folder + " " + String.join(" ", tags)).toLowerCase(Locale.ROOT);
	}

	/** True when the entry carries this tag (any case). */
	public boolean hasTag(String tag) {
		for (String t : tags) if (t.equalsIgnoreCase(tag)) return true;
		return false;
	}
}
