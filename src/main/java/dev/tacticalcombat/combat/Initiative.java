package dev.tacticalcombat.combat;

import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.sheet.Expr;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.Function;

/** Works out one initiative result from a pack formula: rolls the dice (everyone sees them) or just calculates. */
public final class Initiative {
	private Initiative() {}

	public record Result(int total, double tiebreak) {}

	/**
	 * @param roller who the dice are shown as rolled by
	 * @param vars   the sheet's numbers (all zero for a creature without a sheet)
	 */
	public static Result roll(ServerPlayerEntity roller, String label, String formula, String tiebreak, Function<String, Double> vars) {
		int total;
		try {
			Expr.Roll plan = Expr.roll(formula, vars);
			total = DiceService.rollTotal(roller, label, plan.type(), plan.count(), plan.modifier(), 0);
		} catch (RuntimeException noDice) {
			try {
				total = (int) Math.round(Expr.eval(formula, vars)); // a game that sets initiative from a stat, no dice
			} catch (RuntimeException broken) {
				total = 0;
			}
		}
		double tie = 0;
		if (tiebreak != null && !tiebreak.isBlank()) {
			try {
				tie = Expr.eval(tiebreak, vars);
			} catch (RuntimeException ignored) {
				// no tie-break value
			}
		}
		return new Result(total, tie);
	}
}
