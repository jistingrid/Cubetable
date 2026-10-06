package dev.tacticalcombat.sheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sheet format: a data file describing one game's character sheet (what is calculated, what is shown, what each
 * button rolls). The in-game window knows nothing about any particular game; it only draws a format.
 */
public final class SheetFormat {
	public String id;
	public String name = "";
	public String description = "";
	/** Calculated values, in file order: name -> formula. */
	public final Map<String, String> derived = new LinkedHashMap<>();
	/** Values a character may leave out (flags such as "not proficient" = 0). */
	public final Map<String, Double> defaults = new LinkedHashMap<>();
	public final List<Bar> bars = new ArrayList<>();
	public final List<Badge> badges = new ArrayList<>();
	public String titleTemplate = "$name";
	public String subtitleTemplate = "$subtitle";
	public final List<Page> pages = new ArrayList<>();
	/** Where it came from, for messages ("built in" or a file name). */
	public String source = "";

	public record Bar(String label, String value, String max, int color) {}

	public record Badge(String label, String value, boolean signed) {}

	public static final class Page {
		public String title = "";
		public final List<Column> columns = new ArrayList<>();
	}

	public static final class Column {
		public float weight = 1.0f;
		public final List<Section> sections = new ArrayList<>();
	}

	public static final class Section {
		public String title = "";
		public final List<Item> items = new ArrayList<>();
	}

	/** One row: optional mark, label, small text, a calculated value, and roll buttons. */
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
		public final List<Button> buttons = new ArrayList<>();
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

	// ------------------------------------------------------------------ parsing

	public static SheetFormat parse(JsonObject o, String source) {
		SheetFormat f = new SheetFormat();
		f.source = source;
		f.id = str(o, "id", null);
		if (f.id == null || f.id.isBlank()) throw new IllegalArgumentException("missing \"id\"");
		f.name = str(o, "name", f.id);
		f.description = str(o, "description", "");

		if (o.has("derived")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("derived").entrySet()) {
				f.derived.put(e.getKey().toLowerCase(), e.getValue().getAsString());
			}
		}
		if (o.has("defaults")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("defaults").entrySet()) {
				f.defaults.put(e.getKey().toLowerCase(), e.getValue().getAsDouble());
			}
		}
		if (o.has("header")) {
			JsonObject h = o.getAsJsonObject("header");
			f.titleTemplate = str(h, "title", f.titleTemplate);
			f.subtitleTemplate = str(h, "subtitle", f.subtitleTemplate);
			for (JsonElement e : arr(h, "bars")) {
				JsonObject b = e.getAsJsonObject();
				f.bars.add(new Bar(str(b, "label", ""), str(b, "value", "0"), str(b, "max", "0"), color(str(b, "color", "#3cb84a"))));
			}
			for (JsonElement e : arr(h, "badges")) {
				JsonObject b = e.getAsJsonObject();
				f.badges.add(new Badge(str(b, "label", ""), str(b, "value", "0"), bool(b, "signed")));
			}
		}
		for (JsonElement pe : arr(o, "pages")) {
			JsonObject po = pe.getAsJsonObject();
			Page page = new Page();
			page.title = str(po, "title", "Sheet");
			for (JsonElement ce : arr(po, "columns")) {
				JsonObject co = ce.getAsJsonObject();
				Column col = new Column();
				col.weight = (float) (co.has("weight") ? co.get("weight").getAsDouble() : 1.0);
				for (JsonElement se : arr(co, "sections")) {
					JsonObject so = se.getAsJsonObject();
					Section sec = new Section();
					sec.title = str(so, "title", "");
					for (JsonElement ie : arr(so, "items")) {
						sec.items.add(parseItem(ie.getAsJsonObject()));
					}
					col.sections.add(sec);
				}
				page.columns.add(col);
			}
			f.pages.add(page);
		}
		if (f.pages.isEmpty()) throw new IllegalArgumentException("no \"pages\"");
		return f;
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
}
