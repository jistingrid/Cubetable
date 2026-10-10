package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.entity.LivingEntity;

import java.util.Locale;

/**
 * Finds the sheet of a creature. A creature's sheet is a server-held NPC character (kind "npc", linked by a player
 * or DM) whose name is the creature's name: a "Wolf" sheet is used by every wolf, "Wolf 2" or "wolf" too. The sheet
 * gives the creature its defense and, for a Dungeon Master who walks it, its actions. Hit points of a creature are
 * still tracked on the creature itself, so a sheet shared by several wolves does not link their health.
 */
public final class NpcSheets {
	private NpcSheets() {}

	public record Found(String id, ActorSheet sheet) {}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]*\\d+$", "").trim();
	}

	/** The sheet for this creature, or null when there is none. */
	public static Found find(LivingEntity creature) {
		String want = key(creature.getName().getString());
		if (want.isEmpty()) return null;
		for (CharacterStore.Entry e : CharacterStore.all()) {
			if (!e.json.has("kind") || !e.json.get("kind").getAsString().equalsIgnoreCase("npc")) continue;
			String name = e.json.has("text") && e.json.getAsJsonObject("text").has("name")
					? e.json.getAsJsonObject("text").get("name").getAsString() : "";
			if (!key(name).equals(want)) continue;
			SheetFormat f = SheetLibrary.FORMATS.get(e.json.has("format") ? e.json.get("format").getAsString() : "");
			if (f == null || f.combat == null) continue;
			try {
				return new Found(e.id, new ActorSheet(f, CharacterData.parse(e.json.deepCopy(), "")));
			} catch (RuntimeException ex) {
				// a broken sheet is skipped
			}
		}
		return null;
	}
}
