package dev.tacticalcombat.dice;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A parsed roll such as {@code d20}, {@code 2d6+3} or {@code d%}. */
public record DiceSpec(DiceType type, int count, int modifier) {
	public static final int MAX_COUNT = 10;
	private static final Pattern PATTERN = Pattern.compile("(\\d{0,2})d(%|\\d{1,3})([+-]\\d{1,3})?", Pattern.CASE_INSENSITIVE);

	/** Returns null if the text is not a valid dice expression. */
	public static DiceSpec parse(String text) {
		Matcher m = PATTERN.matcher(text.trim().replace(" ", ""));
		if (!m.matches()) return null;
		DiceType type = DiceType.fromSides(m.group(2));
		if (type == null) return null;
		int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
		if (count < 1 || count > MAX_COUNT) return null;
		int mod = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
		return new DiceSpec(type, count, mod);
	}

	public int[] roll(Random random) {
		int[] results = new int[count];
		for (int i = 0; i < count; i++) {
			results[i] = type.roll(random);
		}
		return results;
	}

	/** "2d6+3" */
	public String describe() {
		String s = (count > 1 ? String.valueOf(count) : "") + type.label;
		if (modifier > 0) s += "+" + modifier;
		if (modifier < 0) s += modifier;
		return s;
	}
}
