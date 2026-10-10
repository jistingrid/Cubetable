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

	/** The turn of {@code who} ended: the turn-based conditions on them lose one. */
	public static void onTurnEnd(MinecraftServer server, LivingEntity who) {
		advance(server, who, true);
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
	}
}
