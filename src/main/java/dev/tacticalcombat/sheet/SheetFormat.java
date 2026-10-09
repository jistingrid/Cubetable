package dev.tacticalcombat.sheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sheet format: a data file describing one game's character sheet (what is calculated, what is shown, what each
 * button rolls). The in-game window knows nothing about any particular game; it only draws a format.
 *
 * A format has one or more <b>layouts</b> (kinds of sheet: "character", "npc", ...). They share the format's
 * calculated values and inputs but each has its own header and pages.
 */
public final class SheetFormat {
	public static final String DEFAULT_KIND = "character";

	public String id;
	public String name = "";
	public String description = "";
	/** Name of the colour theme this format prefers (see {@link Theme}); may be empty. */
	public String theme = "";
	/** Calculated values, in file order: name -> formula. */
	public final Map<String, String> derived = new LinkedHashMap<>();
	/** How combat reads this game's sheets; null when the format has no "combat" block. */
	public CombatRules combat;
	/** Values a character may leave out (flags such as "not proficient" = 0). */
	public final Map<String, Double> defaults = new LinkedHashMap<>();
	/** Declared inputs; when a format declares none, they are worked out from the names its formulas use. */
	public final List<Field> fields = new ArrayList<>();
	/** Collections (tables of rows such as gear or weapons), by id, in file order. */
	public final Map<String, Collection> collections = new LinkedHashMap<>();
	/** kind -> layout, in file order. The first one is the default. */
	public final Map<String, Layout> layouts = new LinkedHashMap<>();
	/** Where it came from, for messages ("built in" or a file name). */
	public String source = "";

	/** One input of the character editor. type is "text" (kept in the character's text) or "number". */
	public record Field(String id, String label, String type, String group, String def) {
		public boolean isText() {
			return type.equals("text");
		}
	}

	/** One column of a collection. type: text, note, number, toggle, choice, computed, roll, dice. */
	public static final class Col {
		public String id = "";
		public String label = "";
		public String type = "text";
		/** Relative width in the table. */
		public float width = 1.0f;
		/** Value used when a row does not store one (number, toggle, choice). */
		public double def = 0;
		/** Formula: the shown value of a computed column / the number a roll column shows. */
		public String value;
		public boolean signed;
		/** Not a column of the table: its value is summarised in small text under the row (and edited in the row editor). */
		public boolean detail;
		/** Not shown on the sheet at all, only in the row editor (e.g. a spell's level when the table is grouped by it). */
		public boolean hidden;
		/** Row formula; the cell is only shown when it is above 0 (e.g. "row.attack" for a button that needs a switch). */
		public String enabled;
		/** roll column: the roll formula; {name} in rollLabel becomes the row's name. */
		public String roll;
		public String rollLabel = "{name}";
		/** dice column: optional formula added to the dice text when rolled, e.g. an ability modifier. */
		public String modifier;
		/** choice column: the labels; the row stores the index. */
		public final List<String> options = new ArrayList<>();
		/** choice column used by "groupBy": a heading per option (else the option itself). */
		public final List<String> groupLabels = new ArrayList<>();
		/** cast column: stored-value prefix of the slots it spends ("slots_" -> slots_3 for a level 3 row). */
		public String spend;
		/** cast column: the column holding the row's level (0 = free, like a cantrip). */
		public String spendLevel;
		/** cast column: optional dice column rolled when casting. */
		public String diceCol;

		public boolean isText() {
			return type.equals("text") || type.equals("note") || type.equals("dice");
		}

		public boolean isStored() {
			return isText() || type.equals("number") || type.equals("toggle") || type.equals("choice");
		}
	}

	/** A "total" shown under a table: label, formula, optional maximum ("Carried 40 / 150"). */
	public record Footer(String label, String value, String max) {}

	/** A table of rows the character owns (inventory, weapons, skills ...). */
	public static final class Collection {
		public String id = "";
		public String label = "";
		/** Text of the add button. */
		public String addLabel = "Add";
		/** Which column holds the row's name (first text column unless "nameColumn" says otherwise). */
		public String nameColumn = "";
		/** Column whose value splits the table into headed groups (spell level, item type...). */
		public String groupBy = "";
		/** Entries get a "show to everyone" button. */
		public boolean share;
		public final List<Col> columns = new ArrayList<>();
		public final List<Footer> footers = new ArrayList<>();

		public Col column(String id) {
			for (Col c : columns) if (c.id.equals(id)) return c;
			return null;
		}

		public Col nameCol() {
			Col c = column(nameColumn);
			if (c != null) return c;
			for (Col x : columns) if (x.type.equals("text")) return x;
			return null;
		}
	}

	public record Bar(String label, String value, String max, String temp, int color) {}

	public record Badge(String label, String value, boolean signed) {}

	/** One kind of sheet: a header plus pages. */
	public static final class Layout {
		public String kind = DEFAULT_KIND;
		public String name = "Character";
		public String titleTemplate = "$name";
		public String subtitleTemplate = "$subtitle";
		public final List<Bar> bars = new ArrayList<>();
		public final List<Badge> badges = new ArrayList<>();
		public final List<Page> pages = new ArrayList<>();
	}

	public static final class Page {
		public String id = "";
		public String title = "";
		/** Formula; the page is shown when it is above 0 (or not calculable). Null = always. */
		public String enabled;
		public final List<Column> columns = new ArrayList<>();
	}

	public static final class Column {
		public float weight = 1.0f;
		public final List<Section> sections = new ArrayList<>();
	}

	public static final class Section {
		public String id = "";
		public String title = "";
		public String enabled;
		public final List<Item> items = new ArrayList<>();
	}

	/** One row: optional mark, label, small text, a calculated value, roll buttons, or a widget. */
	public static final class Item {
		public String label = "";
		public String sub = "";
		public String text = "";
		public String value;
		public boolean signed;
		/** Formula; a mark (gold square) is drawn when it is above 0. */
		public String mark;
		/** Clicking the row rolls this. */
		public String roll;
		public String rollLabel;
		public String enabled;
		public final List<Button> buttons = new ArrayList<>();

		/** Widget "table": the id of the collection drawn as a table. */
		public String collection;
		/** Widget "button": stored values to set when pressed, name -> formula (a rest: slots back to their maximum). */
		public final Map<String, String> set = new LinkedHashMap<>();
		/** "" (plain row), "pips", "counter", "cycle", "rollmode" or "table". */
		public String widget = "";
		/** Stored value a widget edits (pips, counter, cycle). */
		public String store;
		/** Formula for the most a pips / counter widget can reach. */
		public String max;
		public int color = 0;
		/** Cycle widget: the values a click steps through. */
		public double[] cycle = {0, 1};
	}

	public static final class Button {
		/** Text shown before the number, e.g. "1d8" for a damage button "1d8+3". */
		public String before = "";
		public String label = "";
		public String value;
		public boolean signed;
		public String roll;
		public String rollLabel;
	}

	// ------------------------------------------------------------------ access

	public Layout layout(String kind) {
		Layout l = layouts.get(kind == null || kind.isEmpty() ? DEFAULT_KIND : kind);
		return l != null ? l : layouts.values().iterator().next();
	}

	// ------------------------------------------------------------------ parsing

	public static SheetFormat parse(JsonObject o, String source) {
		SheetFormat f = new SheetFormat();
		f.source = source;
		f.id = str(o, "id", null);
		if (f.id == null || f.id.isBlank()) throw new IllegalArgumentException("missing \"id\"");
		f.name = str(o, "name", f.id);
		f.description = str(o, "description", "");
		f.theme = str(o, "theme", "");

		if (o.has("combat") && o.get("combat").isJsonObject()) {
			f.combat = CombatRules.parse(o.getAsJsonObject("combat"));
		}
		if (o.has("derived")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("derived").entrySet()) {
				f.derived.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
			}
		}
		for (JsonElement e : arr(o, "fields")) {
			JsonObject fo = e.getAsJsonObject();
			String fid = str(fo, "id", null);
			if (fid == null) continue;
			f.fields.add(new Field(fid.toLowerCase(Locale.ROOT), str(fo, "label", fid), str(fo, "type", "number"),
					str(fo, "group", "Values"), str(fo, "default", "0")));
		}
		if (o.has("defaults")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("defaults").entrySet()) {
				f.defaults.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsDouble());
			}
		}

		for (JsonElement e : arr(o, "collections")) {
			Collection c = parseCollection(e.getAsJsonObject());
			f.collections.put(c.id, c);
		}

		// "sheets": [ {kind, name, header, pages}, ... ]. A format with just top-level header / pages is one
		// "character" layout (the original, still supported shape).
		if (o.has("sheets")) {
			for (JsonElement e : o.getAsJsonArray("sheets")) {
				Layout l = parseLayout(e.getAsJsonObject());
				f.layouts.put(l.kind, l);
			}
		} else {
			f.layouts.put(DEFAULT_KIND, parseLayout(o));
		}
		if (f.layouts.isEmpty()) throw new IllegalArgumentException("no sheets");
		for (Layout l : f.layouts.values()) for (Page p : l.pages) for (Column c : p.columns) for (Section sec : c.sections) {
			for (Item i : sec.items) {
				if (i.widget.equals("table") && (i.collection == null || !f.collections.containsKey(i.collection))) {
					throw new IllegalArgumentException("table widget in '" + sec.title + "' names an unknown collection '" + i.collection + "'");
				}
			}
		}
		return f;
	}

	private static Collection parseCollection(JsonObject o) {
		Collection c = new Collection();
		c.id = str(o, "id", "").toLowerCase(Locale.ROOT);
		if (c.id.isBlank()) throw new IllegalArgumentException("a collection has no \"id\"");
		if (!c.id.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("collection id '" + c.id + "' may only use a-z, 0-9 and _");
		c.label = str(o, "label", c.id);
		c.addLabel = str(o, "addLabel", "Add");
		c.nameColumn = str(o, "nameColumn", "").toLowerCase(Locale.ROOT);
		for (JsonElement e : arr(o, "columns")) {
			JsonObject co = e.getAsJsonObject();
			Col col = new Col();
			col.id = str(co, "id", "").toLowerCase(Locale.ROOT);
			if (!col.id.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("collection '" + c.id + "': bad column id '" + col.id + "'");
			col.label = str(co, "label", col.id);
			col.type = str(co, "type", "text").toLowerCase(Locale.ROOT);
			if (!Set.of("text", "note", "number", "toggle", "choice", "computed", "roll", "dice", "cast").contains(col.type)) {
				throw new IllegalArgumentException("collection '" + c.id + "': column '" + col.id + "' has unknown type '" + col.type + "'");
			}
			col.width = (float) (co.has("width") ? co.get("width").getAsDouble() : (col.type.equals("text") ? 2.5 : 1.0));
			col.def = co.has("default") ? co.get("default").getAsDouble() : 0;
			col.value = str(co, "value", null);
			col.signed = bool(co, "signed");
			col.detail = bool(co, "detail");
			col.hidden = bool(co, "hidden");
			col.enabled = str(co, "enabled", null);
			col.roll = str(co, "roll", null);
			col.rollLabel = str(co, "rollLabel", "{name}");
			col.modifier = str(co, "modifier", null);
			for (JsonElement oe : arr(co, "options")) col.options.add(oe.getAsString());
			for (JsonElement oe : arr(co, "groupLabels")) col.groupLabels.add(oe.getAsString());
			col.spend = str(co, "spend", null);
			col.spendLevel = str(co, "level", null);
			col.diceCol = str(co, "dice", null);
			if (col.type.equals("cast") && (col.spend == null || col.spendLevel == null)) {
				throw new IllegalArgumentException("collection '" + c.id + "': cast column '" + col.id + "' needs \"spend\" and \"level\"");
			}
			if (col.type.equals("choice") && col.options.isEmpty()) throw new IllegalArgumentException("collection '" + c.id + "': choice column '" + col.id + "' has no options");
			if (col.type.equals("computed") && col.value == null) throw new IllegalArgumentException("collection '" + c.id + "': computed column '" + col.id + "' has no value");
			if (col.type.equals("roll") && col.roll == null) throw new IllegalArgumentException("collection '" + c.id + "': roll column '" + col.id + "' has no roll");
			if (c.column(col.id) != null) throw new IllegalArgumentException("collection '" + c.id + "': duplicate column '" + col.id + "'");
			c.columns.add(col);
		}
		if (c.columns.isEmpty()) throw new IllegalArgumentException("collection '" + c.id + "' has no columns");
		c.share = bool(o, "share");
		c.groupBy = str(o, "groupBy", "").toLowerCase(Locale.ROOT);
		if (!c.groupBy.isEmpty() && c.column(c.groupBy) == null) {
			throw new IllegalArgumentException("collection '" + c.id + "': groupBy names an unknown column '" + c.groupBy + "'");
		}
		for (SheetFormat.Col col : c.columns) {
			if (col.type.equals("cast") && (c.column(col.spendLevel) == null || col.diceCol != null && c.column(col.diceCol) == null)) {
				throw new IllegalArgumentException("collection '" + c.id + "': cast column '" + col.id + "' names an unknown level or dice column");
			}
		}
		for (JsonElement e : arr(o, "footer")) {
			JsonObject fo = e.getAsJsonObject();
			c.footers.add(new Footer(str(fo, "label", ""), str(fo, "value", "0"), str(fo, "max", null)));
		}
		return c;
	}

	private static Layout parseLayout(JsonObject o) {
		Layout l = new Layout();
		l.kind = str(o, "kind", DEFAULT_KIND).toLowerCase(Locale.ROOT);
		l.name = str(o, "name", l.kind.substring(0, 1).toUpperCase(Locale.ROOT) + l.kind.substring(1));
		if (o.has("header")) {
			JsonObject h = o.getAsJsonObject("header");
			l.titleTemplate = str(h, "title", l.titleTemplate);
			l.subtitleTemplate = str(h, "subtitle", l.subtitleTemplate);
			for (JsonElement e : arr(h, "bars")) {
				JsonObject b = e.getAsJsonObject();
				l.bars.add(new Bar(str(b, "label", ""), str(b, "value", "0"), str(b, "max", "0"), str(b, "temp", null),
						color(str(b, "color", "#3cb84a"))));
			}
			for (JsonElement e : arr(h, "badges")) {
				JsonObject b = e.getAsJsonObject();
				l.badges.add(new Badge(str(b, "label", ""), str(b, "value", "0"), bool(b, "signed")));
			}
		}
		for (JsonElement pe : arr(o, "pages")) {
			JsonObject po = pe.getAsJsonObject();
			Page page = new Page();
			page.title = str(po, "title", "Sheet");
			page.id = str(po, "id", slug(page.title));
			page.enabled = str(po, "enabled", null);
			for (JsonElement ce : arr(po, "columns")) {
				JsonObject co = ce.getAsJsonObject();
				Column col = new Column();
				col.weight = (float) (co.has("weight") ? co.get("weight").getAsDouble() : 1.0);
				for (JsonElement se : arr(co, "sections")) {
					JsonObject so = se.getAsJsonObject();
					Section sec = new Section();
					sec.title = str(so, "title", "");
					sec.id = str(so, "id", slug(sec.title));
					sec.enabled = str(so, "enabled", null);
					for (JsonElement ie : arr(so, "items")) {
						sec.items.add(parseItem(ie.getAsJsonObject()));
					}
					col.sections.add(sec);
				}
				page.columns.add(col);
			}
			l.pages.add(page);
		}
		if (l.pages.isEmpty()) throw new IllegalArgumentException("sheet \"" + l.kind + "\" has no \"pages\"");
		return l;
	}

	private static Item parseItem(JsonObject o) {
		Item i = new Item();
		i.label = str(o, "label", "");
		i.sub = str(o, "sub", "");
		i.text = str(o, "text", "");
		i.value = str(o, "value", null);
		i.signed = bool(o, "signed");
		i.mark = str(o, "mark", null);
		i.roll = str(o, "roll", null);
		i.rollLabel = str(o, "rollLabel", i.label);
		i.enabled = str(o, "enabled", null);
		i.widget = str(o, "widget", "").toLowerCase(Locale.ROOT);
		i.collection = str(o, "collection", null);
		if (o.has("set") && o.get("set").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("set").entrySet()) {
				i.set.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
			}
		}
		if (i.collection != null) i.collection = i.collection.toLowerCase(Locale.ROOT);
		String store = str(o, "store", null);
		i.store = store == null ? null : store.toLowerCase(Locale.ROOT);
		i.max = str(o, "max", null);
		i.color = color(str(o, "color", "#e0b84c"));
		if (o.has("cycle") && o.get("cycle").isJsonArray()) {
			JsonArray a = o.getAsJsonArray("cycle");
			i.cycle = new double[a.size()];
			for (int k = 0; k < a.size(); k++) i.cycle[k] = a.get(k).getAsDouble();
			if (i.cycle.length == 0) i.cycle = new double[]{0, 1};
		}
		for (JsonElement e : arr(o, "buttons")) {
			JsonObject b = e.getAsJsonObject();
			Button btn = new Button();
			btn.before = str(b, "before", "");
			btn.label = str(b, "label", "");
			btn.value = str(b, "value", null);
			btn.signed = bool(b, "signed");
			btn.roll = str(b, "roll", null);
			btn.rollLabel = str(b, "rollLabel", i.label + (btn.label.isEmpty() ? "" : " " + btn.label));
			i.buttons.add(btn);
		}
		return i;
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	private static boolean bool(JsonObject o, String key) {
		return o.has(key) && o.get(key).getAsBoolean();
	}

	private static JsonArray arr(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	private static int color(String hex) {
		try {
			return 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
		} catch (NumberFormatException e) {
			return 0xFF3CB84A;
		}
	}

	public static String slug(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}

	// ------------------------------------------------------------------ editor inputs

	private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_.]*");
	private static final Set<String> FUNCTIONS = Set.of("floor", "ceil", "round", "abs", "min", "max", "pick");

	/** The inputs the editor shows: the declared ones, else name / subtitle plus every name the formulas need. */
	public List<Field> editableFields() {
		if (!fields.isEmpty()) return fields;
		List<Field> out = new ArrayList<>();
		out.add(new Field("name", "Name", "text", "Identity", "Unnamed"));
		out.add(new Field("subtitle", "Subtitle", "text", "Identity", ""));
		LinkedHashSet<String> names = new LinkedHashSet<>(defaults.keySet());
		List<String> formulas = new ArrayList<>(derived.values());
		for (Layout l : layouts.values()) {
			for (Bar b : l.bars) {
				formulas.add(b.value());
				formulas.add(b.max());
				if (b.temp() != null) formulas.add(b.temp());
			}
			for (Badge b : l.badges) formulas.add(b.value());
			for (Page p : l.pages) for (Column c : p.columns) for (Section s : c.sections) for (Item i : s.items) {
				for (String x : new String[]{i.value, i.mark, i.roll, i.store, i.max}) {
					if (x != null) formulas.add(x);
				}
				for (Button b : i.buttons) {
					if (b.value != null) formulas.add(b.value);
					if (b.roll != null) formulas.add(b.roll);
				}
			}
		}
		for (String formula : formulas) {
			Matcher m = NAME.matcher(formula.toLowerCase(Locale.ROOT));
			while (m.find()) {
				String n = m.group();
				if (FUNCTIONS.contains(n) || n.matches("d\\d*") || derived.containsKey(n) || n.contains(".")) continue;
				names.add(n);
			}
		}
		for (String n : names) {
			out.add(new Field(n, n.replace('_', ' '), "number", "Values", String.valueOf(defaults.getOrDefault(n, 0.0)).replace(".0", "")));
		}
		return out;
	}
}
