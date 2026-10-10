package dev.tacticalcombat.combat;

import dev.tacticalcombat.actor.ActorRegistry;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.entity.LivingEntity;

/**
 * The sheet of a creature in a fight: the character its Actor points to (see {@link ActorRegistry}). A body that is
 * not an Actor has none.
 */
public final class NpcSheets {
	private NpcSheets() {}

	public record Found(String id, ActorSheet sheet) {}

	/** The sheet of this Actor body, or null when it has none (or its game has no combat rules). */
	public static Found find(LivingEntity creature) {
		CharacterStore.Entry e = ActorRegistry.sheetEntryOf(creature);
		if (e == null) return null;
		SheetFormat f = SheetLibrary.FORMATS.get(e.json.has("format") ? e.json.get("format").getAsString() : "");
		if (f == null || f.combat == null) return null;
		try {
			return new Found(e.id, new ActorSheet(f, CharacterData.parse(e.json.deepCopy(), "")));
		} catch (RuntimeException ex) {
			return null; // a broken sheet is skipped
		}
	}
}
