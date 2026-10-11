package dev.tacticalcombat.dice;

import java.util.Locale;

/**
 * What makes a roll critical, as a short text that travels with the roll request.
 *
 * <ul>
 *   <li><b>Attack</b> ({@code A|gte|20|1}): the roll counts as a critical when the natural roll (the dice, without the
 *       modifier) is at least 20 ({@code gte}) or at most it ({@code lte}, for roll-under games), and as a fumble at
 *       1 (the opposite direction). Either number may be empty.</li>
 *   <li><b>Damage</b> ({@code D|double}): how the dice change when the damage is critical: {@code double} (twice the
 *       dice, the modifier once), {@code xN} / {@code multiply:N} (N times the dice and the modifier) or {@code max}
 *       (the modifier grows by the highest the dice can show).</li>
 * </ul>
 * A roll without a spec has no critical: no message, no question.
 */
public record CritSpec(boolean attack, boolean high, Integer crit, Integer fumble, String rule) {
	public static final CritSpec NONE = new CritSpec(false, true, null, null, "");

	public static boolean validRule(String rule) {
		return rule != null && (parseMultiplier(rule) != 0 || "double".equals(rule) || "max".equals(rule));
	}

	/** 0 when the text is not a multiplier rule, else N (2 to 10). */
	private static int parseMultiplier(String rule) {
		String s = rule.toLowerCase(Locale.ROOT).trim();
		if (s.startsWith("multiply:")) s = s.substring(9);
		else if (s.startsWith("x")) s = s.substring(1);
		else return 0;
		try {
			int n = Integer.parseInt(s.trim());
			return n >= 2 && n <= 10 ? n : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static String attackText(boolean high, Integer crit, Integer fumble) {
		if (crit == null && fumble == null) return "";
		return "A|" + (high ? "gte" : "lte") + "|" + (crit == null ? "" : crit) + "|" + (fumble == null ? "" : fumble);
	}

	public static String damageText(String rule) {
		return rule == null || rule.isBlank() ? "" : "D|" + rule.toLowerCase(Locale.ROOT).trim();
	}

	/** Reads a spec; anything malformed is NONE (the roll is then an ordinary one). */
	public static CritSpec parse(String text) {
		if (text == null || text.length() < 3) return NONE;
		String[] p = text.split("\\|", -1);
		try {
			if (p[0].equals("A") && p.length == 4) {
				Integer c = p[2].isEmpty() ? null : Integer.valueOf(p[2]);
				Integer f = p[3].isEmpty() ? null : Integer.valueOf(p[3]);
				return c == null && f == null ? NONE : new CritSpec(true, !p[1].equals("lte"), c, f, "");
			}
			if (p[0].equals("D") && p.length == 2 && validRule(p[1])) return new CritSpec(false, true, null, null, p[1]);
		} catch (NumberFormatException e) {
			// ignored: not a spec
		}
		return NONE;
	}

	public boolean none() {
		return this == NONE || (attack ? crit == null && fumble == null : rule.isEmpty());
	}

	/** An attack roll's natural result: 1 critical, 2 fumble, 0 neither. A critical wins when both apply. */
	public int check(int natural) {
		if (!attack) return 0;
		if (crit != null && (high ? natural >= crit : natural <= crit)) return 1;
		if (fumble != null && (high ? natural <= fumble : natural >= fumble)) return 2;
		return 0;
	}

	/** The dice and modifier of a critical damage roll: {count, modifier}. */
	public int[] damage(DiceType type, int count, int modifier) {
		if (attack || rule.isEmpty()) return new int[] {count, modifier};
		int times = parseMultiplier(rule);
		if (times > 0) return new int[] {count * times, modifier * times};
		if (rule.equals("max")) return new int[] {count, modifier + count * type.sides};
		return new int[] {count * 2, modifier}; // double
	}
}
