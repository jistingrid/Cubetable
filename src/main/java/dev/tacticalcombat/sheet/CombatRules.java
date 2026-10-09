package dev.tacticalcombat.sheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "combat" block of a sheet format: how this game turns a character sheet into combat numbers.
 * The engine only knows generic ideas (distance per square, a movement pool, per-turn resources, ways to
 * buy more movement); every number comes from a formula the pack wrote, evaluated on the character's sheet.
 *
 * <pre>
 * "combat": {
 *   "grid": { "square": 5, "unit": "ft" },                // one grid square = 5 ft (one block in the world)
 *   "resources": { "action": "1", "bonus": "1" },         // refilled every turn (formulas)
 *   "hp": { "now": "hp", "max": "hp_max", "temp": "hp_temp" },   // stored values that are the hit points
 *   "initiative": { "roll": "d20 + init", "mob": "d20", "order": "high", "tiebreak": "init" },
 *   "movement": {
 *     "speed": "speed",                                   // distance of one full move, in grid units
 *     "pool": "speed",                                    // distance granted when the turn starts (default: speed)
 *     "moves": [ { "id": "dash", "label": "Dash", "cost": { "action": 1 }, "grants": "speed",
 *                  "auto": false, "unused": "keep" } ]
 *   }
 * }
 * </pre>
 */
public final class CombatRules {
	/** Grid units per square and the name of the unit (display only). */
	public double square = 1;
	public String unit = "";
	/** Per-turn resources: id -> formula for how many the character gets. */
	public final Map<String, String> resources = new LinkedHashMap<>();
	/** Formula: distance of one full move. */
	public String speed = "";
	/** Formula: distance available as soon as the turn starts. Empty = the speed. */
	public String pool = "";
	public final List<Move> moves = new ArrayList<>();
	/** Which stored values are the hit points; null = combat leaves hit points to Minecraft. */
	public Hp hp;

	/**
	 * @param now  id of the stored value holding current hit points (the one combat changes)
	 * @param max  formula for the maximum
	 * @param temp id of a stored value of temporary hit points, damage drains it first (empty = none)
	 */
	public record Hp(String now, String max, String temp) {}

	/** How this game decides who goes first; null = no initiative roll (the Dungeon Master orders the turns). */
	public Initiative initiative;

	/**
	 * @param roll     formula the player rolls ("d20 + init"); a formula without dice is simply calculated
	 * @param mob      formula for creatures without a sheet (default "d20")
	 * @param low      true: the lowest result goes first
	 * @param tiebreak formula; on equal results the higher value goes first (may be empty)
	 */
	public record Initiative(String roll, String mob, boolean low, String tiebreak) {}

	/**
	 * A way to spend resources on more movement.
	 *
	 * @param grants  formula: distance gained
	 * @param auto    true: bought automatically when a click needs it; false: the player buys it with a button
	 * @param keep    true: distance left over stays usable; false: whatever is not used in that move is lost
	 */
	public record Move(String id, String label, Map<String, Integer> cost, String grants, boolean auto, boolean keep) {}

	public static CombatRules parse(JsonObject o) {
		CombatRules r = new CombatRules();
		if (o.has("grid") && o.get("grid").isJsonObject()) {
			JsonObject g = o.getAsJsonObject("grid");
			if (g.has("square")) r.square = Math.max(0.0001, g.get("square").getAsDouble());
			if (g.has("unit")) r.unit = g.get("unit").getAsString();
		}
		if (o.has("resources") && o.get("resources").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("resources").entrySet()) {
				r.resources.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
			}
		}
		if (o.has("initiative") && o.get("initiative").isJsonObject()) {
			JsonObject i = o.getAsJsonObject("initiative");
			String roll = i.has("roll") ? i.get("roll").getAsString() : "";
			if (!roll.isBlank()) {
				r.initiative = new Initiative(roll, i.has("mob") ? i.get("mob").getAsString() : "d20",
						i.has("order") && i.get("order").getAsString().equalsIgnoreCase("low"),
						i.has("tiebreak") ? i.get("tiebreak").getAsString() : "");
			}
		}
		if (o.has("hp") && o.get("hp").isJsonObject()) {
			JsonObject h = o.getAsJsonObject("hp");
			String now = h.has("now") ? h.get("now").getAsString().toLowerCase(Locale.ROOT) : "";
			if (!now.isBlank()) {
				r.hp = new Hp(now, h.has("max") ? h.get("max").getAsString() : "",
						h.has("temp") ? h.get("temp").getAsString().toLowerCase(Locale.ROOT) : "");
			}
		}
		if (o.has("movement") && o.get("movement").isJsonObject()) {
			JsonObject m = o.getAsJsonObject("movement");
			if (m.has("speed")) r.speed = m.get("speed").getAsString();
			if (m.has("pool")) r.pool = m.get("pool").getAsString();
			if (m.has("moves") && m.get("moves").isJsonArray()) {
				for (JsonElement e : m.getAsJsonArray("moves")) {
					if (!e.isJsonObject()) continue;
					JsonObject mo = e.getAsJsonObject();
					if (!mo.has("id")) continue;
					String id = mo.get("id").getAsString().toLowerCase(Locale.ROOT);
					Map<String, Integer> cost = new LinkedHashMap<>();
					if (mo.has("cost") && mo.get("cost").isJsonObject()) {
						for (Map.Entry<String, JsonElement> c : mo.getAsJsonObject("cost").entrySet()) {
							cost.put(c.getKey().toLowerCase(Locale.ROOT), c.getValue().getAsInt());
						}
					}
					String grants = mo.has("grants") ? mo.get("grants").getAsString() : r.speed;
					boolean keep = !mo.has("unused") || !mo.get("unused").getAsString().equalsIgnoreCase("lost");
					r.moves.add(new Move(id, mo.has("label") ? mo.get("label").getAsString() : id, cost, grants,
							mo.has("auto") && mo.get("auto").getAsBoolean(), keep));
				}
			}
		}
		return r;
	}
}
