package dev.tacticalcombat.sheet;

import java.util.Map;

/** What using an entry costs, from the pack's {@code combat.actions} source for its collection ("action:1,bonus:1"). */
public final class ActionCosts {
	private ActionCosts() {}

	public static String of(SheetFormat f, SheetFormat.Collection coll, SheetContext sc, CharacterData.Row row) {
		if (f == null || f.combat == null) return "";
		StringBuilder out = new StringBuilder();
		for (CombatRules.Source s : f.combat.sources) {
			if (!s.collection().equals(coll.id)) continue;
			for (Map.Entry<String, String> e : s.cost().entrySet()) {
				double v = sc.number(coll, row, e.getValue());
				if (Double.isNaN(v) || v <= 0) continue;
				if (out.length() > 0) out.append(',');
				out.append(e.getKey()).append(':').append(Math.round(v));
			}
			break;
		}
		return out.toString();
	}
}
