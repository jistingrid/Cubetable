package dev.tacticalcombat.combat;

import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetFormat;

/**
 * How initiative works for one combatant, from its game's pack. An empty roll means the game has no initiative
 * roll: there is no button and the Dungeon Master sets the order by hand.
 */
public record InitiativeRule(String roll, String mob, boolean low, String tiebreak) {
	/** No sheet or no pack rules: one d20, highest first (the mod's original behaviour). */
	public static final InitiativeRule DEFAULT = new InitiativeRule("d20", "d20", false, "");

	public boolean hasRoll() {
		return !roll.isBlank();
	}

	public static InitiativeRule of(SheetFormat format) {
		CombatRules.Initiative i = format.combat == null ? null : format.combat.initiative;
		if (format.combat == null) return DEFAULT;
		if (i == null) return new InitiativeRule("", "", false, "");
		return new InitiativeRule(i.roll(), i.mob(), i.low(), i.tiebreak());
	}
}
