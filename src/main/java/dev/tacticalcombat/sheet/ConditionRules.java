package dev.tacticalcombat.sheet;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules of one condition ("when X happens, if Y, do Z"). They live on the condition's row as one text column of
 * type {@code rules}: a line per rule, {@code when|if|effect|target|value}. The engine only knows the lists below;
 * which conditions use them, and with what numbers, is up to the pack and the players.
 *
 * <p>Triggers: {@code turn_start}, {@code turn_end}, {@code expired}. Effects: {@code damage} / {@code heal} (target:
 * bar id, blank = the main health bar; value: a number or dice formula), {@code resource} (target: resource id; value:
 * what the combatant has of it this turn), {@code speed} (value: the movement of this turn, in the game's unit),
 * {@code message} (value: text for chat). The "if" is a formula: the rule applies when it is not 0 (blank: always).
 */
public final class ConditionRules {
	private ConditionRules() {}

	public static final List<String> WHEN = List.of("turn_start", "turn_end", "expired");
	public static final List<String> WHEN_LABEL = List.of("Turn starts", "Turn ends", "Ends");
	public static final List<String> DO = List.of("damage", "heal", "resource", "speed", "message");
	public static final List<String> DO_LABEL = List.of("Damage", "Heal", "Set resource", "Set speed", "Message");

	/** One rule; every field is plain text as typed. */
	public record Rule(String when, String cond, String effect, String target, String value) {
		public String toLine() {
			return when + "|" + cond + "|" + effect + "|" + target + "|" + value;
		}

		/** Whether this effect looks at the target field. */
		public boolean usesTarget() {
			return effect.equals("damage") || effect.equals("heal") || effect.equals("resource");
		}
	}

	public static Rule blank() {
		return new Rule("turn_start", "", "damage", "", "1");
	}

	public static List<Rule> parse(String text) {
		List<Rule> out = new ArrayList<>();
		if (text == null) return out;
		for (String line : text.split("\n")) {
			if (line.isBlank()) continue;
			String[] p = line.split("\\|", -1);
			if (p.length < 5) continue;
			String when = p[0].trim();
			String effect = p[2].trim();
			if (!WHEN.contains(when) || !DO.contains(effect)) continue;
			out.add(new Rule(when, p[1].trim(), effect, p[3].trim(), p[4].trim()));
		}
		return out;
	}

	public static String write(List<Rule> rules) {
		StringBuilder sb = new StringBuilder();
		for (Rule r : rules) sb.append(sb.length() > 0 ? "\n" : "").append(clean(r).toLine());
		return sb.toString();
	}

	/** Separators can not be part of a field. */
	private static Rule clean(Rule r) {
		return new Rule(r.when, c(r.cond), r.effect, c(r.target), c(r.value));
	}

	private static String c(String s) {
		return s.replace('|', '/').replace('\n', ' ').trim();
	}

	/** A short line for the table: "Turn starts: damage 1d4". */
	public static String describe(Rule r) {
		String when = WHEN_LABEL.get(Math.max(0, WHEN.indexOf(r.when)));
		String what = switch (r.effect) {
			case "damage" -> "damage " + r.value + (r.target.isEmpty() ? "" : " to " + r.target);
			case "heal" -> "heal " + r.value + (r.target.isEmpty() ? "" : " to " + r.target);
			case "resource" -> r.target + " = " + r.value;
			case "speed" -> "speed = " + r.value;
			default -> "\"" + r.value + "\"";
		};
		return when + (r.cond.isEmpty() ? "" : " if " + r.cond) + ": " + what;
	}

	public static String describe(String text) {
		StringBuilder sb = new StringBuilder();
		for (Rule r : parse(text)) sb.append(sb.length() > 0 ? "  |  " : "").append(describe(r));
		return sb.toString();
	}
}
