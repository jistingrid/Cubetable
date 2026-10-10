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
 *   "hp": { "now": "hp", "max": "hp_max", "temp": "hp_temp" },   // the health bar (shorthand)
 *   "bars": [ { "id": "sanity", "label": "Sanity", "now": "sanity", "max": "sanity_max", "color": "purple" } ],
 *   "initiative": { "roll": "d20 + init", "mob": "d20", "order": "high", "tiebreak": "init" },
 *   "movement": {
 *     "speed": "speed",                                   // distance of one full move, in grid units
 *     "pool": "speed",                                    // distance granted when the turn starts (default: speed)
 *     "moves": [ { "id": "dash", "label": "Dash", "cost": { "action": 1 }, "grants": "speed",
 *                  "auto": false, "unused": "keep" } ]
 *   },
 *   "defense": { "value": "ac", "label": "AC", "hit": "gte" },   // what an attack roll must beat
 *   "actions": {                                          // the action bar's slots
 *     "showCost": "auto",                                 // auto | always | never (per source too)
 *     "sources": [ { "collection": "weapons", "label": "name", "cost": { "action": "1" }, "icon": "minecraft:iron_sword" },
 *                  { "collection": "spells", "when": "row.prepared", "cost": { "action": "1" },
 *                    "slot": { "store": "slots_", "level": "level" } } ]
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
	/** Every bar combat tracks (hit points, sanity, stamina, mana ...), in display order. */
	public final List<Bar> bars = new ArrayList<>();

	/**
	 * One tracked bar.
	 *
	 * @param id    name of the bar (what effects refer to)
	 * @param label shown next to it
	 * @param now   id of the stored value holding the current amount (the one combat changes)
	 * @param max   formula for the maximum
	 * @param temp  id of a stored value of temporary points that losses drain first (empty = none)
	 * @param color "red", "green", "blue", "purple", "gold", "gray" or "#RRGGBB"
	 * @param vital the bar that is the character's health: damage hits it, and it replaces Minecraft health
	 */
	public record Bar(String id, String label, String now, String max, String temp, String color, boolean vital) {}

	/** The health bar, or null when the game leaves health to Minecraft. */
	public Bar vital() {
		for (Bar b : bars) if (b.vital()) return b;
		return null;
	}

	/** Same as {@link #vital()}: the hit point bar. */
	public Bar hp() {
		return vital();
	}

	/** What an attack roll against this character is compared with; null = no hit check (the roll is just shown). */
	public Defense defense;

	/**
	 * @param value formula for the number to beat (armor class, a difficulty ...)
	 * @param label shown in the result ("AC")
	 * @param hit   "gte" (the roll meets or beats it, default), "gt", "lte" or "lt" (roll-under games)
	 */
	public record Defense(String value, String label, String hit) {
		public boolean hits(double roll, double against) {
			return switch (hit) {
				case "gt" -> roll > against;
				case "lte" -> roll <= against;
				case "lt" -> roll < against;
				default -> roll >= against;
			};
		}
	}

	/** Where the action bar's slots come from, in order. Empty = the bar shows no slots. */
	public final List<Source> sources = new ArrayList<>();
	/** "auto" shows a cost only when it is not the plain default, "always" and "never" force it. */
	public String showCost = "auto";

	/**
	 * A collection whose rows become slots on the action bar.
	 *
	 * @param collection id of the sheet collection (weapons, spells, features ...)
	 * @param label      id of the column holding the row's name
	 * @param cost       resource id -> formula (evaluated on the row, so {@code row.actions} works)
	 * @param when       formula; the row gets a slot only when it is not 0 (empty = every row)
	 * @param slotStore  prefix of the stored values counting uses left ("slots_" -> slots_1, slots_1_max); empty = none
	 * @param slotLevel  id of the column holding the level of the row (0 = free, no slot is spent)
	 * @param show       "auto", "always" or "never": whether the cost is written on the slot
	 * @param icon       item id drawn on the slot ("minecraft:iron_sword"); empty = a default
	 */
	public record Source(String collection, String label, Map<String, String> cost, String when,
						 String slotStore, String slotLevel, String show, String icon) {}

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

	private static Bar parseBar(JsonObject h, String id, String label, String color, boolean vital) {
		String now = h.has("now") ? h.get("now").getAsString().toLowerCase(Locale.ROOT) : "";
		if (now.isBlank()) return null;
		return new Bar(id, h.has("label") ? h.get("label").getAsString() : label, now,
				h.has("max") ? h.get("max").getAsString() : "",
				h.has("temp") ? h.get("temp").getAsString().toLowerCase(Locale.ROOT) : "",
				h.has("color") ? h.get("color").getAsString() : color, vital);
	}

	private static String showMode(String v, String fallback) {
		String m = v.toLowerCase(Locale.ROOT);
		return m.equals("auto") || m.equals("always") || m.equals("never") ? m : fallback;
	}

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
		if (o.has("hp") && o.get("hp").isJsonObject()) { // shorthand for the one health bar
			Bar b = parseBar(o.getAsJsonObject("hp"), "hp", "HP", "red", true);
			if (b != null) r.bars.add(b);
		}
		if (o.has("bars") && o.get("bars").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("bars")) {
				if (!e.isJsonObject()) continue;
				JsonObject bo = e.getAsJsonObject();
				String id = bo.has("id") ? bo.get("id").getAsString().toLowerCase(Locale.ROOT) : "";
				if (id.isBlank()) continue;
				boolean vital = bo.has("vital") && bo.get("vital").getAsBoolean();
				Bar b = parseBar(bo, id, id, "blue", vital);
				if (b != null) {
					r.bars.removeIf(x -> x.id().equals(b.id()) || vital && x.vital());
					r.bars.add(b);
				}
			}
		}
		if (o.has("defense") && o.get("defense").isJsonObject()) {
			JsonObject d = o.getAsJsonObject("defense");
			if (d.has("value") && !d.get("value").getAsString().isBlank()) {
				String hit = d.has("hit") ? d.get("hit").getAsString().toLowerCase(Locale.ROOT) : "gte";
				if (!hit.equals("gt") && !hit.equals("lte") && !hit.equals("lt")) hit = "gte";
				r.defense = new Defense(d.get("value").getAsString(),
						d.has("label") ? d.get("label").getAsString() : "Defense", hit);
			}
		}
		if (o.has("actions") && o.get("actions").isJsonObject()) {
			JsonObject a = o.getAsJsonObject("actions");
			if (a.has("showCost")) r.showCost = showMode(a.get("showCost").getAsString(), "auto");
			if (a.has("sources") && a.get("sources").isJsonArray()) {
				for (JsonElement e : a.getAsJsonArray("sources")) {
					if (!e.isJsonObject()) continue;
					JsonObject so = e.getAsJsonObject();
					if (!so.has("collection")) continue;
					Map<String, String> cost = new LinkedHashMap<>();
					if (so.has("cost") && so.get("cost").isJsonObject()) {
						for (Map.Entry<String, JsonElement> c : so.getAsJsonObject("cost").entrySet()) {
							cost.put(c.getKey().toLowerCase(Locale.ROOT), c.getValue().getAsString());
						}
					}
					String store = "";
					String level = "";
					if (so.has("slot") && so.get("slot").isJsonObject()) {
						JsonObject sl = so.getAsJsonObject("slot");
						store = sl.has("store") ? sl.get("store").getAsString().toLowerCase(Locale.ROOT) : "";
						level = sl.has("level") ? sl.get("level").getAsString().toLowerCase(Locale.ROOT) : "";
					}
					r.sources.add(new Source(so.get("collection").getAsString().toLowerCase(Locale.ROOT),
							so.has("label") ? so.get("label").getAsString().toLowerCase(Locale.ROOT) : "name",
							cost, so.has("when") ? so.get("when").getAsString() : "", store, level,
							showMode(so.has("showCost") ? so.get("showCost").getAsString() : "", ""),
							so.has("icon") ? so.get("icon").getAsString() : ""));
				}
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
