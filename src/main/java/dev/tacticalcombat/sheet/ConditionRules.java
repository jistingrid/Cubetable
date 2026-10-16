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
 * {@code message} (value: text for chat). Roll triggers {@code roll} (the owner makes any roll but a damage roll),
 * {@code attack} (the owner attacks) and {@code attacked} (an attack is aimed at the owner) go with {@code advantage},
 * {@code disadvantage} (a single d20; one of each cancels out) and {@code bonus} (value: a number added to the roll);
 * their "if" may use {@code distance}, the squares between attacker and target.
 *
 * <p>Event triggers: {@code round_start}, {@code moved} (the owner finished a move; {@code moved} = squares),
 * {@code hit} / {@code miss} (the owner's attack hits or misses), {@code struck} / {@code dodged} (an attack on the
 * owner hits or misses), {@code damaged} / {@code healed} (a damage prompt was applied; {@code damage} = the amount),
 * {@code downed} (the owner dropped to 0) and {@code applied} (the condition was just added). They take the effects
 * {@code damage}, {@code heal}, {@code message}, {@code apply} (add the preset condition named by the target) and
 * {@code remove} (remove the condition named by the target; blank: this one). In "if" and in messages
 * ({@code {damage}}) the names above are replaced by their numbers.
 *
 * <p><b>Rules on entries</b> (a weapon, a feature, a spell with a {@code rules} column) work the same, with the
 * entry's attacker as the owner and the <i>target</i> of its roll as who the effects happen to: {@code hit} /
 * {@code miss} fire when its attack roll hits or misses, {@code used} when its damage roll is made. Formulas
 * (damage, DC) use the attacker's sheet. The {@code check} effect asks the affected creature to roll the pack's check
 * named by the target ({@code str_save}), against the DC in the value (blank: no DC); {@code check_passed} /
 * {@code check_failed} then fire on the same rule list. The "if" is a formula: the rule applies when it is not 0 (blank: always).
 */
public final class ConditionRules {
	private ConditionRules() {}

	public static final List<String> WHEN = List.of("turn_start", "turn_end", "expired", "roll", "attack", "attacked",
			"round_start", "moved", "hit", "miss", "struck", "dodged", "damaged", "healed", "downed", "applied",
			"used", "check_passed", "check_failed", "incoming");
	public static final List<String> WHEN_LABEL = List.of("Turn starts", "Turn ends", "Ends", "I roll", "I attack", "I'm attacked",
			"Round starts", "I finish moving", "My attack hits", "My attack misses", "I'm hit", "An attack misses me", "I take damage",
			"I'm healed", "I go down", "Applied", "I use it", "Check passed", "Check failed", "Damage is offered to me");
	public static final List<String> DO = List.of("damage", "heal", "resource", "speed", "message", "advantage", "disadvantage", "bonus", "apply", "remove", "check", "hint", "disengage");
	public static final List<String> DO_LABEL = List.of("Damage", "Heal", "Set resource", "Set speed", "Message", "Advantage", "Disadvantage", "Bonus", "Add condition", "Remove condition", "Ask for a roll", "Suggest half / full", "Disengage");

	/** One rule; every field is plain text as typed. */
	public record Rule(String when, String cond, String effect, String target, String value, String who) {
		public Rule(String when, String cond, String effect, String target, String value) {
			this(when, cond, effect, target, value, "");
		}

		public String toLine() {
			return when + "|" + cond + "|" + effect + "|" + target + "|" + value + (who.isEmpty() ? "" : "|" + who);
		}

		public Rule withWhen(String v) {
			return new Rule(v, cond, effect, target, value, who);
		}

		public Rule withCond(String v) {
			return new Rule(when, v, effect, target, value, who);
		}

		public Rule withEffect(String v) {
			return new Rule(when, cond, v, target, value, who);
		}

		public Rule withTarget(String v) {
			return new Rule(when, cond, effect, v, value, who);
		}

		public Rule withValue(String v) {
			return new Rule(when, cond, effect, target, v, who);
		}

		public Rule withWho(String v) {
			return new Rule(when, cond, effect, target, value, v);
		}

		/** Whether this effect looks at the target field. */
		public boolean usesTarget() {
			return effect.equals("damage") || effect.equals("heal") || effect.equals("resource") || effect.equals("apply") || effect.equals("remove") || effect.equals("check");
		}
	}

	/** The rules text of an entry (a weapon, a feature ...): its first {@code rules} column, cut to what a request can carry. */
	public static String rowRules(SheetFormat.Collection coll, CharacterData.Row row) {
		for (SheetFormat.Col col : coll.columns) {
			if (!col.isRules()) continue;
			String t = row.texts.getOrDefault(col.id, "").trim();
			if (t.length() <= MAX_SENT) return t;
			int cut = t.lastIndexOf('\n', MAX_SENT);
			return cut <= 0 ? "" : t.substring(0, cut);
		}
		return "";
	}

	/** "collection:index" of a row on its character, so the server can find the same row; "" when it is not on it. */
	public static String ref(SheetFormat.Collection coll, CharacterData c, CharacterData.Row row) {
		List<CharacterData.Row> rows = c.collections.getOrDefault(coll.id, List.of());
		for (int i = 0; i < rows.size(); i++) if (rows.get(i) == row) return coll.id + ":" + i;
		return "";
	}

	/** What a roll request can carry of an entry's rules. */
	public static final int MAX_SENT = 900;

	/** Who an effect happens to: the default for where the rules are (blank), the entry's user, or its target. */
	public static final List<String> WHO = List.of("", "self", "target");
	public static final List<String> WHO_LABEL = List.of("Auto", "Self", "Target");

	public static Rule blank() {
		return new Rule("turn_start", "", "damage", "", "1");
	}

	public static List<Rule> parse(String text) {
		List<Rule> out = new ArrayList<>();
		if (text == null) return out;
		for (String line : text.split("\n")) {
			if (line.isBlank()) continue;
			String[] p = line.split("\\|", -1);
			if (p.length < 4) continue;
			if (p.length < 6) p = java.util.Arrays.copyOf(p, 6); // a missing value is blank
			for (int i = 0; i < 6; i++) if (p[i] == null) p[i] = "";
			String when = p[0].trim();
			String effect = p[2].trim();
			if (!WHEN.contains(when) || !DO.contains(effect)) continue;
			out.add(new Rule(when, p[1].trim(), effect, p[3].trim(), p[4].trim(), WHO.contains(p[5].trim()) ? p[5].trim() : ""));
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
		return new Rule(r.when, c(r.cond), r.effect, c(r.target), c(r.value), r.who);
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
			case "advantage" -> "advantage";
			case "disadvantage" -> "disadvantage";
			case "bonus" -> "bonus " + r.value;
			case "apply" -> "add " + r.target;
			case "check" -> "ask for " + r.target + (r.value.isEmpty() ? "" : " vs DC " + r.value);
			case "remove" -> "remove " + (r.target.isEmpty() ? "itself" : r.target);
			case "disengage" -> "disengage (no attacks of opportunity this turn)";
			case "hint" -> "suggest " + (r.value.isEmpty() ? "half" : r.value) + " damage";
			default -> "\"" + r.value + "\"";
		};
		return when + (r.cond.isEmpty() ? "" : " if " + r.cond) + ": " + what + (r.who.isEmpty() ? "" : " (" + r.who + ")");
	}

	public static String describe(String text) {
		StringBuilder sb = new StringBuilder();
		for (Rule r : parse(text)) sb.append(sb.length() > 0 ? "  |  " : "").append(describe(r));
		return sb.toString();
	}
}
