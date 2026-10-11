package dev.tacticalcombat.sheet;

import dev.tacticalcombat.dice.CritSpec;

/** Turns a column's critical settings into the text that travels with a roll ({@link CritSpec}); "" = no critical. */
public final class Crits {
	private Crits() {}

	/** A roll column's critical range and fumble, worked out on the row (the range is usually a hidden column). */
	public static String attack(SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, SheetFormat.Col col) {
		if (col.crit == null && col.fumble == null) return "";
		return CritSpec.attackText(!col.critLow, number(sc, coll, row, col.crit), number(sc, coll, row, col.fumble));
	}

	/** A dice column's critical damage rule, or "" when its damage has no critical. */
	public static String damage(SheetFormat.Col col) {
		return col.critDamage == null ? "" : CritSpec.damageText(col.critDamage);
	}

	private static Integer number(SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, String formula) {
		if (formula == null || formula.isBlank()) return null;
		double v = sc.number(coll, row, formula);
		return Double.isNaN(v) || Double.isInfinite(v) ? null : (int) Math.round(v);
	}
}
