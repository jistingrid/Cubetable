package dev.tacticalcombat.combat;

import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a combatant gets at the start of its turn, worked out from its sheet and its game's {@link CombatRules}:
 * movement in grid squares, the per-turn resources, and the ways to buy more movement.
 */
public final class TurnSetup {
	/** One way to buy movement, with its distance already in squares. */
	public record MoveOption(String id, String label, Map<String, Integer> cost, int grant, boolean auto, boolean keep) {}

	/** Distance of one grid square in the game's own unit (display only) and that unit's name. */
	public double square = 1;
	public String unit = "";
	/** Squares available when the turn starts. */
	public int pool;
	/** Resource id -> how many the combatant gets each turn. */
	public final Map<String, Integer> resources = new LinkedHashMap<>();
	public final List<MoveOption> moves = new ArrayList<>();

	public List<MoveOption> moves() {
		return moves;
	}

	/** No sheet: plain defaults (also used for mobs). */
	public static TurnSetup basic(double poolSquares) {
		TurnSetup t = new TurnSetup();
		t.pool = (int) Math.floor(poolSquares + 1.0E-6);
		t.resources.put("action", 1);
		t.resources.put("bonus", 1);
		return t;
	}

	/** The setup for a player: from their Active Actor (see {@link ActorSheet}), else the defaults. */
	public static TurnSetup of(ServerPlayerEntity player) {
		ActorSheet sheet = ActorSheet.of(player);
		if (sheet != null) {
			try {
				return compute(sheet.format(), sheet.character());
			} catch (RuntimeException ex) {
				// a broken sheet must not stop the fight: fall through to the defaults
			}
		}
		return basic(CombatConfig.PLAYER_MOVEMENT);
	}

	/** Pure calculation, no Minecraft involved. */
	public static TurnSetup compute(SheetFormat format, CharacterData character) {
		CombatRules r = format.combat;
		SheetContext sc = new SheetContext(format, character);
		TurnSetup t = new TurnSetup();
		t.square = r.square;
		t.unit = r.unit;

		String poolFormula = !r.pool.isBlank() ? r.pool : r.speed;
		t.pool = poolFormula.isBlank() ? 0 : squares(sc.number(poolFormula), r.square);

		if (r.resources.isEmpty()) {
			t.resources.put("action", 1);
			t.resources.put("bonus", 1);
		} else {
			for (Map.Entry<String, String> e : r.resources.entrySet()) {
				t.resources.put(e.getKey(), Math.max(0, (int) Math.floor(sc.number(e.getValue()) + 1.0E-6)));
			}
		}
		for (CombatRules.Move m : r.moves) {
			int grant = m.grants().isBlank() ? 0 : squares(sc.number(m.grants()), r.square);
			t.moves.add(new MoveOption(m.id(), m.label(), m.cost(), grant, m.auto(), m.keep()));
		}
		return t;
	}

	private static int squares(double distance, double square) {
		return Math.max(0, (int) Math.floor(distance / square + 1.0E-6));
	}
}
