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
 *   "grid": { "square": 5, "unit": "ft", "diagonals": "free" },   // one grid square = 5 ft (one block in the world);
 *                                                         // diagonals: "free" (a corner step costs one square) or "none" (default: straight steps only)
 *   "resources": { "action": "1", "bonus": "1" },         // refilled every turn (formulas)
 *   "attackResource": "action",                            // what an attack spends ("" = nothing); default: "action", else the first
 *   "resourceStyles": { "bonus": { "label": "Bonus", "color": "#5fb7a8", "shape": "diamond" } },
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
 *     "trays": [ { "id": "spells", "label": "Spells", "icon": "minecraft:enchanted_book" } ],   // pop-up lists above the bar
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
	/** Whether a step to a corner square is allowed and costs one square ("grid.diagonals": "free"); default straight steps only. */
	public boolean diagonals;
	public String unit = "";
	/** Per-turn resources: id -> formula for how many the character gets. */
	public final Map<String, String> resources = new LinkedHashMap<>();

	/** How a resource looks on the turn panel: its name, a colour ("gold", "#5fb7a8") and "circle" or "diamond". All optional. */
	public record ResStyle(String label, String color, String shape) {}

	public final Map<String, ResStyle> resourceStyles = new LinkedHashMap<>();

	/** The resource an attack spends; null = the default, see {@link #attackResourceId()}. "" = attacks cost nothing of the turn. */
	public String attackResource;

	/**
	 * The resource an attack spends each time: what the pack names in "attackResource", else "action" when the game
	 * has one (or lists no resources at all), else its first resource. "" when attacks are free.
	 */
	public String attackResourceId() {
		if (attackResource != null) return attackResource;
		if (resources.isEmpty() || resources.containsKey("action")) return "action";
		return resources.keySet().iterator().next();
	}
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

	/** The id of the bar that damage lowers ("damage": { "bar": ... } in the pack); "" = the health bar. */
	public String damageBar = "";
	/**
	 * When a fight ends by itself: "sides" (default) when one side - the players or everyone else - has nobody left;
	 * "manual" only when the Dungeon Master ends it (a game where fights are not always players against enemies).
	 */
	public boolean manualEnd;

	/** What the game calls it when a creature loses that bar ("damage", "harm", "stress"). */
	public String damageNoun = "damage";

	/** The bar damage lowers and healing raises: the one the pack names, else the health bar; null when there is none. */
	public Bar damageTarget() {
		if (!damageBar.isEmpty()) for (Bar b : bars) if (b.id().equalsIgnoreCase(damageBar)) return b;
		return vital();
	}

	/** The health bar, or null when the game leaves health to Minecraft. */
	public Bar vital() {
		for (Bar b : bars) if (b.vital()) return b;
		return null;
	}

	/** Same as {@link #vital()}: the hit point bar. */
	public Bar hp() {
		return vital();
	}

	/** What happens at 0 hit points; null = nothing special (the sheet just shows 0). */
	public Downed downed;

	/** Which collection is the list of conditions and how its durations count down; null = nothing counts down. */
	public Conditions conditions;

	/**
	 * @param collection the collection holding the conditions
	 * @param duration   its number column with the time left (0 or less = until removed)
	 * @param unit       its choice column naming the unit
	 * @param turns      the option of that column that counts turns (ticks down when the creature's turn ends in a fight)
	 * @param seconds    the option that counts seconds (ticks down in real time, not while time is paused)
	 */
	public record Conditions(String collection, String duration, String unit, String turns, String seconds) {}

	/**
	 * @param label   the state's name ("Downed", "Dying" ...)
	 * @param all     true: every sheet with a health bar is downed; false (default): only player characters are, a
	 *                creature's sheet (kind "npc") is simply dead at 0
	 * @param save    the death save, or null when the game has none (the DM then decides what happens)
	 */
	public record Downed(String label, boolean all, Save save) {}

	/**
	 * A death save: roll {@code dice} (+ {@code modifier}), succeed when it {@code hit}s {@code target}.
	 * {@code successes} before {@code failures} makes the character stable, the other way round dead.
	 * A natural {@code natSuccess} gets up with {@code natHeal} points; a natural {@code natFailure} counts
	 * {@code natFailures} failures; each hit taken while down counts {@code damageFails}.
	 */
	public record Save(String label, String dice, String modifier, double target, String hit, int successes, int failures,
					   int natSuccess, int natHeal, int natFailure, int natFailures, int damageFails) {}

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
	/** Pop-up lists the action bar offers (spells, features ...); a source with a {@code tray} puts its rows there instead of on the bar. */
	public final List<Tray> trays = new ArrayList<>();

	/** A check a creature can be asked to roll (a saving throw, an ability check ...): {@code roll} is a roll formula on its sheet. */
	public record Check(String id, String label, String roll) {}

	/** The checks of {@code combat.checks}; Roll requests ({@code check} rules, later other mechanics) name one by id. */
	public final List<Check> checks = new ArrayList<>();

	/** Which rolls can have advantage or disadvantage and which way the better result goes ("rollMode" in the pack). */
	public dev.tacticalcombat.dice.RollMode rollMode = dev.tacticalcombat.dice.RollMode.DEFAULT;

	/** The die this game rolls when nothing says which ("d20", "d%", "d6"): a check without a formula, a creature's initiative. */
	public String defaultDie = "d20";

	/** {@link #defaultDie} as a die type (a d20 when the pack names none that exists). */
	public dev.tacticalcombat.dice.DiceType defaultType() {
		String t = defaultDie.toLowerCase(Locale.ROOT).trim();
		dev.tacticalcombat.dice.DiceType type = t.startsWith("d") ? dev.tacticalcombat.dice.DiceType.fromSides(t.substring(1)) : null;
		return type == null ? dev.tacticalcombat.dice.DiceType.D20 : type;
	}

	public Check check(String id) {
		for (Check c : checks) if (c.id().equalsIgnoreCase(id)) return c;
		return null;
	}

	/**
	 * A tray: a button on the action bar that opens a list of entries, grouped under headings and scrollable.
	 *
	 * @param id    what a source's {@code tray} refers to
	 * @param label the button's name and the list's title
	 * @param icon  item id drawn on the button
	 */
	public record Tray(String id, String label, String icon) {}

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
	 * @param tray       id of a tray: its rows are listed there (and can be pinned to the bar) instead of filling the bar; empty = on the bar
	 * @param group      id of a column whose value heads the rows in the tray (a spell's level); empty = no headings
	 * @param info       id of a column whose text is the slot's tooltip (default: "notes" when the collection has one)
	 * @param area       id of a text column that says how many creatures or what area the entry affects
	 *                   ("sphere 20", "cone 15", "line 30x5", "targets 3"); empty = one target
	 */
	public record Source(String collection, String label, Map<String, String> cost, String when,
						 String slotStore, String slotLevel, String show, String icon,
						 String tray, String group, String info, String area) {}

	/** How this game decides who goes first; null = no initiative roll (the Dungeon Master orders the turns). */
	public Initiative initiative;

	/**
	 * @param roll     formula the player rolls ("d20 + init"); a formula without dice is simply calculated
	 * @param mob      formula for creatures without a sheet (default "d20")
	 * @param low      true: the lowest result goes first
	 * @param tiebreak formula; on equal results the higher value goes first (may be empty)
	 * @param grouping "" (everyone in their own place), "side" (each side acts together, the side with the best result
	 *                 first), "players_first" or "enemies_first" (fixed phases)
	 */
	public record Initiative(String roll, String mob, boolean low, String tiebreak, String grouping) {}

	/**
	 * A way to spend resources on more movement.
	 *
	 * @param grants  formula: distance gained
	 * @param auto    true: bought automatically when a click needs it; false: the player buys it with a button
	 * @param keep    true: distance left over stays usable; false: whatever is not used in that move is lost
	 */
	public record Move(String id, String label, Map<String, Integer> cost, String grants, boolean auto, boolean keep, boolean disengage) {}

	/**
	 * Attacks of opportunity. {@code reach}: formula, in squares (default 1): a creature is engaged with an opposing one
	 * standing within it. {@code reaction}: the resource an opportunity attack spends ("" = free). Without this block
	 * the game has no attacks of opportunity.
	 */
	public record Engagement(String reach, String reaction) {}

	public Engagement engagement;

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
			if (g.has("diagonals") && g.get("diagonals").isJsonPrimitive()) {
				String d = g.get("diagonals").getAsString().trim().toLowerCase(Locale.ROOT);
				r.diagonals = d.equals("free") || d.equals("true") || d.equals("on");
			}
		}
		if (o.has("attackResource") && o.get("attackResource").isJsonPrimitive()) r.attackResource = o.get("attackResource").getAsString().toLowerCase(Locale.ROOT).trim();
		if (o.has("resourceStyles") && o.get("resourceStyles").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("resourceStyles").entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				JsonObject so = e.getValue().getAsJsonObject();
				r.resourceStyles.put(e.getKey().toLowerCase(Locale.ROOT), new ResStyle(
						so.has("label") ? so.get("label").getAsString() : "",
						so.has("color") ? so.get("color").getAsString() : "",
						so.has("shape") ? so.get("shape").getAsString().toLowerCase(Locale.ROOT) : ""));
			}
		}
		if (o.has("resources") && o.get("resources").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("resources").entrySet()) {
				r.resources.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
			}
		}
		if (o.has("rollMode") && o.get("rollMode").isJsonObject()) {
			JsonObject m = o.getAsJsonObject("rollMode");
			String die = m.has("die") ? m.get("die").getAsString().toLowerCase(Locale.ROOT).trim() : "d20";
			dev.tacticalcombat.dice.DiceType type = die.startsWith("d") ? dev.tacticalcombat.dice.DiceType.fromSides(die.substring(1)) : null;
			boolean high = !m.has("better") || !m.get("better").getAsString().equalsIgnoreCase("low");
			r.rollMode = type == null ? dev.tacticalcombat.dice.RollMode.NONE : new dev.tacticalcombat.dice.RollMode(type, high);
		}
		if (o.has("endsWhen") && o.get("endsWhen").isJsonPrimitive()) r.manualEnd = o.get("endsWhen").getAsString().equalsIgnoreCase("manual");
		if (o.has("damage") && o.get("damage").isJsonObject()) {
			JsonObject d = o.getAsJsonObject("damage");
			if (d.has("bar")) r.damageBar = d.get("bar").getAsString().toLowerCase(Locale.ROOT).trim();
			if (d.has("noun") && !d.get("noun").getAsString().isBlank()) r.damageNoun = d.get("noun").getAsString().trim();
		}
		if (o.has("defaultDie") && o.get("defaultDie").isJsonPrimitive()) r.defaultDie = o.get("defaultDie").getAsString();
		if (o.has("initiative") && o.get("initiative").isJsonObject()) {
			JsonObject i = o.getAsJsonObject("initiative");
			String roll = i.has("roll") ? i.get("roll").getAsString() : "";
			String grouping = i.has("grouping") ? i.get("grouping").getAsString().toLowerCase(Locale.ROOT).trim() : "";
			if (!grouping.equals("side") && !grouping.equals("players_first") && !grouping.equals("enemies_first")) grouping = "";
			if (!roll.isBlank() || !grouping.isEmpty()) { // grouping alone: no roll, the sides take turns in a fixed order
				r.initiative = new Initiative(roll, i.has("mob") ? i.get("mob").getAsString() : r.defaultDie,
						i.has("order") && i.get("order").getAsString().equalsIgnoreCase("low"),
						i.has("tiebreak") ? i.get("tiebreak").getAsString() : "", grouping);
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
		if (o.has("conditions") && o.get("conditions").isJsonObject()) {
			JsonObject d = o.getAsJsonObject("conditions");
			if (d.has("collection") && d.has("duration") && d.has("unit")) {
				r.conditions = new Conditions(d.get("collection").getAsString().toLowerCase(Locale.ROOT),
						d.get("duration").getAsString().toLowerCase(Locale.ROOT), d.get("unit").getAsString().toLowerCase(Locale.ROOT),
						d.has("turns") ? d.get("turns").getAsString() : "turns",
						d.has("seconds") ? d.get("seconds").getAsString() : "seconds");
			}
		}
		if (o.has("checks") && o.get("checks").isJsonArray()) {
			for (JsonElement ce : o.getAsJsonArray("checks")) {
				if (!ce.isJsonObject()) continue;
				JsonObject co = ce.getAsJsonObject();
				if (!co.has("id") || !co.has("roll")) continue;
				String id = co.get("id").getAsString().toLowerCase(Locale.ROOT);
				r.checks.add(new Check(id, co.has("label") ? co.get("label").getAsString() : capitalise(id), co.get("roll").getAsString()));
			}
		}
		if (o.has("downed") && o.get("downed").isJsonObject()) {
			JsonObject d = o.getAsJsonObject("downed");
			Save save = null;
			if (d.has("save") && d.get("save").isJsonObject()) {
				JsonObject s = d.getAsJsonObject("save");
				String hit = s.has("hit") ? s.get("hit").getAsString().toLowerCase(Locale.ROOT) : "gte";
				if (!hit.equals("gt") && !hit.equals("lte") && !hit.equals("lt")) hit = "gte";
				save = new Save(s.has("label") ? s.get("label").getAsString() : "Death save",
						s.has("dice") ? s.get("dice").getAsString() : "d20",
						s.has("modifier") ? s.get("modifier").getAsString() : "0",
						s.has("target") ? s.get("target").getAsDouble() : 10, hit,
						Math.max(1, s.has("successes") ? s.get("successes").getAsInt() : 3),
						Math.max(1, s.has("failures") ? s.get("failures").getAsInt() : 3),
						s.has("natSuccess") ? s.get("natSuccess").getAsInt() : 0,
						s.has("natHeal") ? s.get("natHeal").getAsInt() : 1,
						s.has("natFailure") ? s.get("natFailure").getAsInt() : 0,
						s.has("natFailures") ? s.get("natFailures").getAsInt() : 2,
						s.has("damageFails") ? s.get("damageFails").getAsInt() : 1);
			}
			r.downed = new Downed(d.has("label") ? d.get("label").getAsString() : "Downed",
					d.has("applies") && d.get("applies").getAsString().equalsIgnoreCase("all"), save);
		}
		if (o.has("actions") && o.get("actions").isJsonObject()) {
			JsonObject a = o.getAsJsonObject("actions");
			if (a.has("showCost")) r.showCost = showMode(a.get("showCost").getAsString(), "auto");
			if (a.has("trays") && a.get("trays").isJsonArray()) {
				for (JsonElement e : a.getAsJsonArray("trays")) {
					if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
					JsonObject to = e.getAsJsonObject();
					String id = to.get("id").getAsString().toLowerCase(Locale.ROOT);
					r.trays.add(new Tray(id, to.has("label") ? to.get("label").getAsString() : capitalise(id),
							to.has("icon") ? to.get("icon").getAsString() : ""));
				}
			}
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
							so.has("icon") ? so.get("icon").getAsString() : "",
							so.has("tray") ? so.get("tray").getAsString().toLowerCase(Locale.ROOT) : "",
							so.has("group") ? so.get("group").getAsString().toLowerCase(Locale.ROOT) : "",
							so.has("info") ? so.get("info").getAsString().toLowerCase(Locale.ROOT) : "",
							so.has("area") ? so.get("area").getAsString().toLowerCase(Locale.ROOT) : ""));
				}
			}
			// a source may name a tray the block did not declare: it gets one called after its id
			for (Source s : r.sources) {
				if (!s.tray().isEmpty() && r.trays.stream().noneMatch(t -> t.id().equals(s.tray()))) {
					r.trays.add(new Tray(s.tray(), capitalise(s.tray()), ""));
				}
			}
		}
		if (o.has("engagement") && o.get("engagement").isJsonObject()) {
			JsonObject g = o.getAsJsonObject("engagement");
			r.engagement = new Engagement(g.has("reach") ? g.get("reach").getAsString() : "1",
					g.has("reaction") ? g.get("reaction").getAsString().toLowerCase(Locale.ROOT).trim() : "");
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
							mo.has("auto") && mo.get("auto").getAsBoolean(), keep,
							mo.has("disengage") && mo.get("disengage").getAsBoolean()));
				}
			}
		}
		return r;
	}

	private static String capitalise(String id) {
		String t = id.replace('_', ' ').trim();
		return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}
}
