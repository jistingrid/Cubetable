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
		runRules(server, who, "turn_end", null, null);
		advance(server, who, true);
	}

	/** The turn of {@code who} began (its resources and movement are set): their "turn starts" rules run. */
	public static void onTurnStart(MinecraftServer server, LivingEntity who, Combatant turn) {
		runRules(server, who, "turn_start", turn, null);
	}

	/**
	 * Runs the rules ({@link ConditionRules}) of the conditions on {@code who} that fire on {@code when}. {@code only}
	 * limits it to one row (a condition that just ended). {@code turn} is the combatant whose turn it is, for the
	 * effects that change this turn's resources and movement.
	 */
	private static void runRules(MinecraftServer server, LivingEntity who, String when, Combatant turn, CharacterData.Row only) {
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
		String who2 = Damage.nameOf(who);
		for (CharacterData.Row row : rows) {
			String name = nameCol == null ? "A condition" : row.texts.getOrDefault(nameCol.id, "A condition");
			for (ConditionRules.Rule r : ConditionRules.parse(row.texts.getOrDefault(rulesCol.id, ""))) {
				if (!r.when().equals(when)) continue;
				CharacterData fresh = parsed(entry);
				SheetContext sc = new SheetContext(f, fresh == null ? c : fresh);
				if (!r.cond().isEmpty()) {
					double on = sc.number(coll, row, r.cond());
					if (Double.isNaN(on) || on == 0) continue;
				}
				String prefix = who2 + ": " + name + " - ";
				switch (r.effect()) {
					case "damage", "heal" -> {
						int amount = Math.max(0, rollValue(sc, coll, row, r.value()));
						String bar = r.target().isEmpty() ? SheetHealth.vitalId(who) : r.target();
						boolean heal = r.effect().equals("heal");
						if (bar == null || !SheetHealth.change(server, who, bar, heal ? amount : -amount)) break;
						server.getPlayerManager().broadcast(Text.literal(prefix + (heal ? "heals " : "takes ") + amount
								+ (heal ? " " : " damage ") + "(" + bar + ")"), false);
					}
					case "resource" -> {
						if (turn == null || r.target().isEmpty()) break;
						double v = sc.number(coll, row, r.value());
						if (Double.isNaN(v)) break;
						int n = Math.max(0, (int) Math.floor(v + 1.0E-6));
						turn.left.put(r.target(), n);
						turn.max.put(r.target(), n);
					}
					case "speed" -> {
						if (turn == null) break;
						double v = sc.number(coll, row, r.value());
						if (Double.isNaN(v)) break;
						double square = f.combat.square <= 0 ? 1 : f.combat.square;
						turn.moveBudget = Math.max(0, Math.floor(v / square + 1.0E-6));
					}
					default -> server.getPlayerManager().broadcast(Text.literal(prefix + r.value()), false);
				}
			}
		}
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
			Expr.Roll roll = sc.plan(coll, row, formula);
			int total = roll.modifier();
			for (int i = 0; i < roll.count(); i++) total += 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1, roll.type().sides));
			return total;
		} catch (RuntimeException noDice) {
			double v = sc.number(coll, row, formula);
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
		for (CharacterData.Row row : ended) runRules(server, who, "expired", null, row);
	}
}
