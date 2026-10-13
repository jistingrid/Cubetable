package dev.tacticalcombat.combat;

import dev.tacticalcombat.dice.RollMode;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.entity.LivingEntity;

/** The roll-mode rule of the game a creature plays by (see {@link RollMode}). */
public final class RollModes {
	private RollModes() {}

	public static RollMode of(LivingEntity who) {
		if (who == null) return RollMode.DEFAULT;
		dev.tacticalcombat.character.CharacterStore.Entry entry = SheetHealth.entryOf(who);
		SheetFormat f = entry == null ? null : SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		return f == null || f.combat == null ? RollMode.DEFAULT : f.combat.rollMode;
	}
}
