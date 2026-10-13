package dev.tacticalcombat.dice;

/**
 * Which rolls can be rolled with a second chance (the Normal / Advantage / Disadvantage control and the "advantage"
 * rules of conditions), and which way the better result goes. A game sets it in {@code combat.rollMode}; the default
 * is the d20 one: a single d20, advantage keeps the higher.
 *
 * @param die   the die whose single rolls can be rolled twice; null = this game has no such thing
 * @param advantageKeepsHigh true: advantage keeps the higher of two, disadvantage the lower; false (a game where low is
 *                           good, like roll-under) the other way round
 */
public record RollMode(DiceType die, boolean advantageKeepsHigh) {
	public static final RollMode DEFAULT = new RollMode(DiceType.D20, true);
	public static final RollMode NONE = new RollMode(null, true);

	/** Can a roll of {@code count} dice of {@code type} be rolled twice? Only a lone die can. */
	public boolean appliesTo(DiceType type, int count) {
		return die != null && type == die && count == 1;
	}

	/** What to keep when the roll is made with mode 1 (advantage) or 2 (disadvantage): 1 the highest die, 2 the lowest. */
	public int keepFor(int mode) {
		return (mode == 1) == advantageKeepsHigh ? 1 : 2;
	}
}
