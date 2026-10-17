package dev.tacticalcombat.sheet;

import java.util.Map;

/** What a rest does to a sheet, as plain maths on its numbers (no Minecraft): used by the sheet's buttons and by the server. */
public final class RestMath {
	private RestMath() {}

	/**
	 * Sets stored values from formulas (hit points back to their maximum ...) and sets a counter of every matching row of a
	 * collection to a formula (a feature's uses back to its maximum). Returns how many numbers changed.
	 */
	public static int apply(SheetContext sc, Map<String, String> set, java.util.List<SheetFormat.Refill> refill) {
		int changed = 0;
		for (Map.Entry<String, String> e : set.entrySet()) {
			double v = sc.number(e.getValue());
			if (Double.isNaN(v)) continue;
			v = Math.max(0, v);
			if (sc.character.values.getOrDefault(e.getKey(), Double.NaN) != v) changed++;
			sc.character.values.put(e.getKey(), v);
		}
		for (SheetFormat.Refill rf : refill) {
			SheetFormat.Collection coll = sc.format.collections.get(rf.collection());
			if (coll == null) continue;
			SheetFormat.Col match = rf.matchColumn().isEmpty() ? null : coll.column(rf.matchColumn());
			for (CharacterData.Row row : sc.character.rows(coll.id)) {
				if (match != null) {
					int k = (int) Math.round(row.values.getOrDefault(match.id, match.def));
					if (match.options.isEmpty() || k < 0 || k >= match.options.size()) continue;
					if (!rf.values().contains(match.options.get(k).trim().toLowerCase(java.util.Locale.ROOT))) continue;
				}
				double v = sc.number(coll, row, rf.to());
				if (Double.isNaN(v)) continue;
				v = Math.max(0, v);
				if (row.values.getOrDefault(rf.column(), 0.0) != v) changed++;
				row.values.put(rf.column(), v);
			}
		}
		return changed;
	}

	/** Hit dice left on the sheet (0 when the game has none). */
	public static int hitDiceLeft(SheetContext sc, CombatRules.HitDice hd) {
		if (hd == null) return 0;
		return (int) Math.max(0, Math.round(sc.character.values.getOrDefault(hd.store(), 0.0)));
	}

	public static int hitDiceMax(SheetContext sc, CombatRules.HitDice hd) {
		if (hd == null) return 0;
		double v = sc.number(hd.max());
		return Double.isNaN(v) ? 0 : (int) Math.max(0, Math.floor(v + 1.0E-6));
	}

	/** Sides of one hit die (at least 2). */
	public static int dieSides(SheetContext sc, CombatRules.HitDice hd) {
		double v = hd == null ? Double.NaN : sc.number(hd.die());
		return Double.isNaN(v) ? 8 : Math.max(2, (int) Math.round(v));
	}

	/** The formula of one hit die roll with the sides filled in ("d8 + con_mod"). */
	public static String healFormula(SheetContext sc, CombatRules.HitDice hd) {
		return hd.heal().replace("{die}", String.valueOf(dieSides(sc, hd)));
	}

	/** Gives hit dice back (the type's formula), never above the maximum. Returns how many came back. */
	public static int regainHitDice(SheetContext sc, CombatRules.HitDice hd, CombatRules.RestType type) {
		if (hd == null || type.regainHitDice().isBlank()) return 0;
		double n = sc.number(type.regainHitDice());
		if (Double.isNaN(n) || n <= 0) return 0;
		int left = hitDiceLeft(sc, hd);
		int now = Math.min(hitDiceMax(sc, hd), left + (int) Math.floor(n + 1.0E-6));
		if (now <= left) return 0;
		sc.character.values.put(hd.store(), (double) now);
		return now - left;
	}
}
