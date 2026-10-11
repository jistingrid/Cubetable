package dev.tacticalcombat.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tacticalcombat.actor.ActorRecord;
import dev.tacticalcombat.actor.ActorRegistry;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.CharacterSync;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.ConditionRules;
import dev.tacticalcombat.sheet.Expr;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Iterator;
import java.util.List;

/**
 * Counts the durations of the conditions on a sheet down, as the pack says ({@code combat.conditions}): conditions
 * measured in <b>turns</b> lose one when their owner's turn ends in a fight, those in <b>seconds</b> lose one every
 * real second (not while time is paused). At 0 the condition is removed from the sheet and chat says so. A duration
 * of 0 (or less) is "until removed" and never changes.
 */
public final class Conditions {
	private Conditions() {}

	/** Every second: the second-based conditions of everyone in the world. */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0 || TimePause.paused) return;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (!p.isSpectator()) advance(server, p, false);
		}
		for (ActorRecord r : ActorRegistry.all()) {
			LivingEntity body = ActorRegistry.bodyOf(server, r);
			if (body != null) advance(server, body, false);
		}
	}

	/** The turn of {@code who} ended: their conditions' "turn ends" rules run, then the turn-based ones lose one. */
	public static void onTurnEnd(MinecraftServer server, LivingEntity who) {
		runRules(server, who, "turn_end", null, null, java.util.Map.of());
		advance(server, who, true);
	}

	/** The turn of {@code who} began (its resources and movement are set): their "turn starts" rules run. */
	public static void onTurnStart(MinecraftServer server, LivingEntity who, Combatant turn) {
		runRules(server, who, "turn_start", turn, null, java.util.Map.of());
	}

	/** An event happened to {@code who} (see {@link ConditionRules}): their conditions' rules for it run. */
	public static void fire(MinecraftServer server, LivingEntity who, String when, java.util.Map<String, Integer> vars) {
		if (server == null || who == null) return;
		runRules(server, who, when, null, null, vars);
	}

	/** The server's copy of a character was replaced by the owner's edit: conditions that are new on it are "applied". */
	public static void onSheetPushed(MinecraftServer server, CharacterStore.Entry entry, com.google.gson.JsonObject before) {
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		CombatRules.Conditions rule = f == null || f.combat == null ? null : f.combat.conditions;
		SheetFormat.Collection coll = rule == null ? null : f.collections.get(rule.collection());
		SheetFormat.Col nameCol = coll == null ? null : coll.nameCol();
		if (nameCol == null) return;
		CharacterData now = parsed(entry);
		CharacterData old;
		try {
			old = CharacterData.parse(before.deepCopy(), "");
		} catch (RuntimeException ex) {
			return;
		}
		if (now == null) return;
		java.util.Map<String, Integer> had = new java.util.HashMap<>();
		for (CharacterData.Row r : old.collections.getOrDefault(rule.collection(), List.of())) had.merge(r.texts.getOrDefault(nameCol.id, ""), 1, Integer::sum);
		List<CharacterData.Row> added = new java.util.ArrayList<>();
		for (CharacterData.Row r : now.collections.getOrDefault(rule.collection(), List.of())) {
			String n = r.texts.getOrDefault(nameCol.id, "");
			int left = had.getOrDefault(n, 0);
			if (left > 0) had.put(n, left - 1);
			else added.add(r);
		}
		if (added.isEmpty()) return;
		LivingEntity who = null;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			CharacterStore.Entry e = dev.tacticalcombat.character.Actors.entryOf(p.getUuid());
			if (e != null && e.id.equals(entry.id)) who = p;
		}
		if (who == null) {
			for (ActorRecord r : ActorRegistry.all()) {
				LivingEntity body = ActorRegistry.bodyOf(server, r);
				CharacterStore.Entry e = body == null ? null : ActorRegistry.sheetEntryOf(body);
				if (e != null && e.id.equals(entry.id)) who = body;
			}
		}
		if (who == null) return;
		for (CharacterData.Row r : added) runRules(server, who, "applied", null, r, java.util.Map.of());
	}

	/**
	 * Runs the rules ({@link ConditionRules}) of the conditions on {@code who} that fire on {@code when}. {@code only}
	 * limits it to one row (a condition that just ended). {@code turn} is the combatant whose turn it is, for the
	 * effects that change this turn's resources and movement.
	 */
	private static int depth;

	private static void runRules(MinecraftServer server, LivingEntity who, String when, Combatant turn, CharacterData.Row only,
								 java.util.Map<String, Integer> vars) {
		if (depth >= 3) return; // rules that add conditions that add conditions ... stop here
		depth++;
		try {
			runRules0(server, who, when, turn, only, vars);
		} finally {
			depth--;
		}
	}

	/** The names of {@code vars} in a formula are replaced by their numbers ("damage > 5"); in a message "{damage}" is. */
	private static String sub(String text, java.util.Map<String, Integer> vars, boolean message) {
		for (java.util.Map.Entry<String, Integer> e : vars.entrySet()) {
			text = message ? text.replace("{" + e.getKey() + "}", e.getValue().toString())
					: text.replaceAll("\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b", e.getValue().toString());
		}
		return text;
	}

	/** Where a list of rules comes from: the formulas' sheet, the entry's own row (conditions), its name. */
	private record Src(CharacterStore.Entry entry, SheetFormat f, CharacterData fallback, SheetFormat.Collection coll,
					   CharacterData.Row row, String name, List<ConditionRules.Rule> rules, Combatant turn, boolean selfCondition) {
		SheetContext sc() {
			CharacterData d = parsed(entry);
			return new SheetContext(f, d == null ? fallback : d);
		}

		double num(String formula) {
			SheetContext c = sc();
			return coll == null || row == null ? c.number(formula) : c.number(coll, row, formula);
		}
	}

	private static void runRules0(MinecraftServer server, LivingEntity who, String when, Combatant turn, CharacterData.Row only,
								  java.util.Map<String, Integer> vars) {
		CharacterStore.Entry entry = SheetHealth.entryOf(who);
		if (entry == null || !entry.json.has("collections")) return;
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		CombatRules.Conditions rule = f == null || f.combat == null ? null : f.combat.conditions;
		if (rule == null) return;
		SheetFormat.Collection coll = f.collections.get(rule.collection());
		if (coll == null) return;
		SheetFormat.Col rulesCol = null;
		for (SheetFormat.Col col : coll.columns) if (col.isRules()) rulesCol = col;
		if (rulesCol == null) return;
		CharacterData c = parsed(entry);
		if (c == null) return;
		List<CharacterData.Row> rows = only != null ? List.of(only) : new java.util.ArrayList<>(c.collections.getOrDefault(rule.collection(), List.of()));
		SheetFormat.Col nameCol = coll.nameCol();
		for (CharacterData.Row row : rows) {
			String name = nameCol == null ? "A condition" : row.texts.getOrDefault(nameCol.id, "A condition");
			List<ConditionRules.Rule> rules = ConditionRules.parse(row.texts.getOrDefault(rulesCol.id, ""));
			executeRules(server, who, new Src(entry, f, c, coll, row, name, rules, turn, true), when, vars);
		}
	}

	/**
	 * Runs the rules of an entry (a weapon, a feature ...) that fire on {@code when}: formulas use {@code attacker}'s
	 * sheet, the effects happen to {@code subject} (the target of the entry's roll).
	 */
	public static void runEntry(MinecraftServer server, LivingEntity attacker, LivingEntity subject, String rulesText,
								String entryName, String when, java.util.Map<String, Integer> vars) {
		if (server == null || attacker == null || subject == null || rulesText == null || rulesText.isBlank() || depth >= 3) return;
		List<ConditionRules.Rule> rules = ConditionRules.parse(rulesText);
		boolean any = false;
		for (ConditionRules.Rule r : rules) if (r.when().equals(when)) any = true;
		if (!any) return;
		CharacterStore.Entry entry = SheetHealth.entryOf(attacker);
		SheetFormat f = entry == null ? null : SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		CharacterData c = entry == null ? null : parsed(entry);
		if (f == null || c == null) return;
		depth++;
		try {
			executeRules(server, subject, new Src(entry, f, c, null, null, entryName, rules, null, false), when, vars);
		} finally {
			depth--;
		}
	}

	private static void executeRules(MinecraftServer server, LivingEntity subject, Src s, String when, java.util.Map<String, Integer> vars) {
		for (ConditionRules.Rule r : s.rules()) {
			if (!r.when().equals(when)) continue;
			if (!r.cond().isEmpty()) {
				double on = s.num(sub(r.cond(), vars, false));
				if (Double.isNaN(on) || on == 0) continue;
			}
			effect(server, subject, s, r, vars);
		}
	}

	private static void effect(MinecraftServer server, LivingEntity subject, Src s, ConditionRules.Rule r, java.util.Map<String, Integer> vars) {
		String prefix = Damage.nameOf(subject) + ": " + s.name() + " - ";
		switch (r.effect()) {
			case "damage", "heal" -> {
				int amount = Math.max(0, rollValue(s.sc(), s.coll(), s.row(), sub(r.value(), vars, false)));
				String bar = r.target().isEmpty() ? SheetHealth.vitalId(subject) : r.target();
				boolean heal = r.effect().equals("heal");
				if (bar == null || !SheetHealth.change(server, subject, bar, heal ? amount : -amount)) return;
				server.getPlayerManager().broadcast(Text.literal(prefix + (heal ? "heals " : "takes ") + amount
						+ (heal ? " " : " damage ") + "(" + bar + ")"), false);
			}
			case "resource" -> {
				if (s.turn() == null || r.target().isEmpty()) return;
				double v = s.num(r.value());
				if (Double.isNaN(v)) return;
				int n = Math.max(0, (int) Math.floor(v + 1.0E-6));
				s.turn().left.put(r.target(), n);
				s.turn().max.put(r.target(), n);
			}
			case "speed" -> {
				if (s.turn() == null) return;
				double v = s.num(r.value());
				if (Double.isNaN(v)) return;
				double square = s.f().combat.square <= 0 ? 1 : s.f().combat.square;
				s.turn().moveBudget = Math.max(0, Math.floor(v / square + 1.0E-6));
			}
			case "apply" -> addCondition(server, subject, r.target());
			case "remove" -> removeCondition(server, subject, r.target().isEmpty() && s.selfCondition() ? s.name() : r.target());
			case "check" -> {
				if (r.target().isEmpty()) return;
				double dc = r.value().isEmpty() ? Double.NaN : s.num(sub(r.value(), vars, false));
				final boolean hasDc = !Double.isNaN(dc);
				String asker = s.selfCondition() ? s.name() : Damage.nameOf(subject).equals(s.name()) ? "" : s.name();
				Checks.request(server, subject, asker, r.target(), hasDc ? (int) Math.round(dc) : -1, true, res -> {
					if (!hasDc) return;
					java.util.Map<String, Integer> after = new java.util.HashMap<>(vars);
					after.put("check", res.total());
					executeRules(server, subject, s, res.passed() ? "check_passed" : "check_failed", after);
				});
			}
			case "advantage", "disadvantage", "bonus" -> {
			}
			default -> server.getPlayerManager().broadcast(Text.literal(prefix + sub(r.value(), vars, true)), false);
		}
	}

	/** The format and the conditions collection of a creature's sheet, or null when it has none. */
	private record Conds(CharacterStore.Entry entry, SheetFormat f, SheetFormat.Collection coll) {}

	private static Conds condsOf(LivingEntity who) {
		CharacterStore.Entry entry = SheetHealth.entryOf(who);
		if (entry == null) return null;
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		CombatRules.Conditions rule = f == null || f.combat == null ? null : f.combat.conditions;
		SheetFormat.Collection coll = rule == null ? null : f.collections.get(rule.collection());
		return coll == null ? null : new Conds(entry, f, coll);
	}

	/** Adds the pack's preset condition of that name to {@code who}'s sheet; its own "applied" rules then run. */
	private static void addCondition(MinecraftServer server, LivingEntity who, String name) {
		Conds k = condsOf(who);
		SheetFormat.Col nameCol = k == null ? null : k.coll().nameCol();
		CharacterData c = k == null ? null : parsed(k.entry());
		if (nameCol == null || c == null || name.isEmpty()) return;
		CharacterData.Row preset = null;
		for (CharacterData.Row p : k.coll().presets) if (p.texts.getOrDefault(nameCol.id, "").equalsIgnoreCase(name)) preset = p;
		if (preset == null) return;
		CharacterData.Row row = preset.copy();
		c.rows(k.coll().id).add(row);
		save(server, k.entry(), c);
		server.getPlayerManager().broadcast(Text.literal(Damage.nameOf(who) + ": " + row.texts.get(nameCol.id) + " applied."), false);
		runRules(server, who, "applied", null, row, java.util.Map.of());
	}

	/** Removes the first condition of that name from {@code who}'s sheet; its "ends" rules run. */
	private static void removeCondition(MinecraftServer server, LivingEntity who, String name) {
		Conds k = condsOf(who);
		SheetFormat.Col nameCol = k == null ? null : k.coll().nameCol();
		CharacterData c = k == null ? null : parsed(k.entry());
		if (nameCol == null || c == null || name.isEmpty()) return;
		List<CharacterData.Row> rows = c.rows(k.coll().id);
		CharacterData.Row gone = null;
		for (CharacterData.Row r : rows) {
			if (gone == null && r.texts.getOrDefault(nameCol.id, "").equalsIgnoreCase(name)) gone = r;
		}
		if (gone == null) return;
		rows.remove(gone);
		save(server, k.entry(), c);
		server.getPlayerManager().broadcast(Text.literal(Damage.nameOf(who) + ": " + name + " ended."), false);
		runRules(server, who, "expired", null, gone, java.util.Map.of());
	}

	private static void save(MinecraftServer server, CharacterStore.Entry entry, CharacterData c) {
		JsonObject json = c.toJson();
		json.addProperty("id", entry.id);
		entry.json = json;
		CharacterSync.changedByServer(server, entry);
	}

	/** What the roll rules of one creature's conditions add up to for one roll. */
	public record RollEffect(int advantage, int disadvantage, int bonus, List<String> notes) {
		public static final RollEffect NONE = new RollEffect(0, 0, 0, List.of());
	}

	/**
	 * Adds up the roll rules ({@code advantage}, {@code disadvantage}, {@code bonus}) of {@code who}'s conditions that
	 * fire on any of {@code whens}. {@code distance} (squares, -1 when there is no target) can be used in their "if".
	 */
	public static RollEffect forRoll(LivingEntity who, List<String> whens, int distance) {
		CharacterStore.Entry entry = SheetHealth.entryOf(who);
		if (entry == null || !entry.json.has("collections")) return RollEffect.NONE;
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		CombatRules.Conditions rule = f == null || f.combat == null ? null : f.combat.conditions;
		SheetFormat.Collection coll = rule == null ? null : f.collections.get(rule.collection());
		if (coll == null) return RollEffect.NONE;
		SheetFormat.Col rulesCol = null;
		for (SheetFormat.Col col : coll.columns) if (col.isRules()) rulesCol = col;
		CharacterData c = parsed(entry);
		if (rulesCol == null || c == null) return RollEffect.NONE;
		SheetContext sc = new SheetContext(f, c);
		SheetFormat.Col nameCol = coll.nameCol();
		int adv = 0, dis = 0, bonus = 0;
		List<String> notes = new java.util.ArrayList<>();
		for (CharacterData.Row row : c.collections.getOrDefault(rule.collection(), List.of())) {
			String name = nameCol == null ? "A condition" : row.texts.getOrDefault(nameCol.id, "A condition");
			for (ConditionRules.Rule r : ConditionRules.parse(row.texts.getOrDefault(rulesCol.id, ""))) {
				if (!whens.contains(r.when())) continue;
				if (!r.cond().isEmpty()) {
					double on = sc.number(coll, row, r.cond().replaceAll("\\bdistance\\b", Integer.toString(distance)));
					if (Double.isNaN(on) || on == 0) continue;
				}
				switch (r.effect()) {
					case "advantage" -> {
						adv++;
						notes.add(name + ": advantage");
					}
					case "disadvantage" -> {
						dis++;
						notes.add(name + ": disadvantage");
					}
					case "bonus" -> {
						double v = sc.number(coll, row, r.value());
						if (Double.isNaN(v) || Math.round(v) == 0) break;
						bonus += (int) Math.round(v);
						notes.add(name + ": " + SheetContext.format(v, true));
					}
					default -> {
					}
				}
			}
		}
		return new RollEffect(adv, dis, bonus, notes);
	}

	private static CharacterData parsed(CharacterStore.Entry entry) {
		try {
			return CharacterData.parse(entry.json.deepCopy(), "");
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** A number or a dice formula ({@code 1d4 + 2}) of a rule, rolled here when it has dice; 0 when it does not work. */
	private static int rollValue(SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, String formula) {
		try {
			Expr.Roll roll = coll == null || row == null ? sc.plan(formula) : sc.plan(coll, row, formula);
			int total = roll.modifier();
			for (int i = 0; i < roll.count(); i++) total += 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1, roll.type().sides));
			return total;
		} catch (RuntimeException noDice) {
			double v = coll == null || row == null ? sc.number(formula) : sc.number(coll, row, formula);
			return Double.isNaN(v) ? 0 : (int) Math.round(v);
		}
	}

	private static void advance(MinecraftServer server, LivingEntity who, boolean turns) {
		CharacterStore.Entry entry = SheetHealth.entryOf(who);
		if (entry == null || !entry.json.has("collections") || !entry.json.get("collections").isJsonObject()) return;
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		CombatRules.Conditions rule = f == null || f.combat == null ? null : f.combat.conditions;
		if (rule == null) return;
		JsonObject cols = entry.json.getAsJsonObject("collections");
		JsonElement listed = cols.get(rule.collection());
		if (listed == null || !listed.isJsonArray() || ((JsonArray) listed).isEmpty()) return; // nothing to parse

		SheetFormat.Collection coll = f.collections.get(rule.collection());
		SheetFormat.Col unitCol = coll == null ? null : coll.column(rule.unit());
		SheetFormat.Col durCol = coll == null ? null : coll.column(rule.duration());
		if (unitCol == null || durCol == null) return;
		int unitIndex = -1;
		for (int i = 0; i < unitCol.options.size(); i++) {
			if (unitCol.options.get(i).equalsIgnoreCase(turns ? rule.turns() : rule.seconds())) unitIndex = i;
		}
		if (unitIndex < 0) return;

		CharacterData c;
		try {
			c = CharacterData.parse(entry.json.deepCopy(), "");
		} catch (RuntimeException ex) {
			return;
		}
		List<CharacterData.Row> rows = c.collections.get(rule.collection());
		if (rows == null) return;
		SheetFormat.Col nameCol = coll.nameCol();
		String who2 = Damage.nameOf(who);
		boolean changed = false;
		List<CharacterData.Row> ended = new java.util.ArrayList<>();
		for (Iterator<CharacterData.Row> it = rows.iterator(); it.hasNext();) {
			CharacterData.Row row = it.next();
			double left = row.values.getOrDefault(durCol.id, durCol.def);
			if (left <= 0) continue;
			int unit = (int) Math.round(row.values.getOrDefault(unitCol.id, unitCol.def));
			if (unit != unitIndex) continue;
			left -= 1;
			changed = true;
			if (left <= 0) {
				it.remove();
				ended.add(row);
				String name = nameCol == null ? "A condition" : row.texts.getOrDefault(nameCol.id, "A condition");
				server.getPlayerManager().broadcast(Text.literal(who2 + ": " + name + " ended."), false);
			} else {
				row.values.put(durCol.id, left);
			}
		}
		if (!changed) return;
		JsonObject json = c.toJson();
		json.addProperty("id", entry.id);
		entry.json = json;
		CharacterSync.changedByServer(server, entry);
		// after the save, so a rule that changes the sheet (damage when poison wears off) is not overwritten
		for (CharacterData.Row row : ended) runRules(server, who, "expired", null, row, java.util.Map.of());
	}
}
