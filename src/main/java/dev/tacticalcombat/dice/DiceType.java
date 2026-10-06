package dev.tacticalcombat.dice;

import java.util.Random;

/** The full tabletop set: d4, d6, d8, d10, d% (d100), d12 and d20. */
public enum DiceType {
	D4(4, "d4"),
	D6(6, "d6"),
	D8(8, "d8"),
	D10(10, "d10"),
	D100(100, "d%"),
	D12(12, "d12"),
	D20(20, "d20");

	public final int sides;
	public final String label;

	DiceType(int sides, String label) {
		this.sides = sides;
		this.label = label;
	}

	/** One roll of this die, 1..sides. */
	public int roll(Random random) {
		return random.nextInt(sides) + 1;
	}

	/** Accepts "20", "100" and "%" (the part after the "d"). Returns null for anything that is not a real die. */
	public static DiceType fromSides(String text) {
		if (text.equals("%")) return D100;
		int n;
		try {
			n = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return null;
		}
		for (DiceType t : values()) {
			if (t.sides == n) return t;
		}
		return null;
	}
}
