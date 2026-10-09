package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Actors;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** The sheet a player fights with: their Active Actor, else any character of theirs whose pack has combat rules. */
public record ActorSheet(SheetFormat format, CharacterData character) {
	public static ActorSheet of(ServerPlayerEntity player) {
		List<CharacterStore.Entry> candidates = new ArrayList<>();
		CharacterStore.Entry active = Actors.entryOf(player.getUuid());
		if (active != null) candidates.add(active);
		for (CharacterStore.Entry e : CharacterStore.all()) {
			if (e != active && e.owner.equals(player.getUuid())) candidates.add(e);
		}
		for (CharacterStore.Entry e : candidates) {
			SheetFormat f = SheetLibrary.FORMATS.get(e.json.has("format") ? e.json.get("format").getAsString() : "");
			if (f == null || f.combat == null) continue;
			try {
				return new ActorSheet(f, CharacterData.parse(e.json.deepCopy(), ""));
			} catch (RuntimeException ex) {
				// a broken sheet must not stop the fight
			}
		}
		return null;
	}
}
