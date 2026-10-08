package dev.tacticalcombat.sheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** One saved character: which format it uses plus its raw numbers and text. The format does the maths. */
public final class CharacterData {
	public String file = "";
	public String format = "";
	/** Which layout of the format this character uses ("character", "npc", ...). */
	public String kind = SheetFormat.DEFAULT_KIND;
	/** Theme id; empty = the format's own theme. */
	public String theme = "";
	/** Hidden pages ("page:<id>") and sections ("section:<page>/<id>") of this character's sheet. */
	public final java.util.Set<String> hidden = new java.util.LinkedHashSet<>();
	/** Section order per column ("<page>/<column index>" -> section ids); sections not listed keep their place after. */
	public final Map<String, java.util.List<String>> order = new java.util.LinkedHashMap<>();
	/** Server link id; empty = a purely local character. */
	public String link = "";
	/** Server version this copy was last in sync with. */
	public long linkVersion;
	/** True for someone else's character received from the server (never written to disk). */
	public boolean remote;
	public String ownerName = "";
	public final Map<String, String> texts = new HashMap<>();
	public final Map<String, Double> values = new HashMap<>();

	/** Rows of the format's collections (gear, weapons ...), by collection id. */
	public final Map<String, java.util.List<Row>> collections = new java.util.LinkedHashMap<>();

	/** One row of a collection: text-like columns in texts, number / toggle / choice columns in values. */
	public static final class Row {
		public final Map<String, String> texts = new HashMap<>();
		public final Map<String, Double> values = new HashMap<>();

		public Row copy() {
			Row r = new Row();
			r.texts.putAll(texts);
			r.values.putAll(values);
			return r;
		}
	}

	public java.util.List<Row> rows(String collection) {
		return collections.computeIfAbsent(collection, k -> new java.util.ArrayList<>());
	}

	public static CharacterData parse(JsonObject o, String file) {
		CharacterData c = new CharacterData();
		c.file = file;
		c.format = o.has("format") ? o.get("format").getAsString() : "";
		if (c.format.isBlank()) throw new IllegalArgumentException("missing \"format\"");
		if (o.has("kind")) c.kind = o.get("kind").getAsString().toLowerCase();
		if (o.has("theme")) c.theme = o.get("theme").getAsString().toLowerCase();
		if (o.has("link")) c.link = o.get("link").getAsString();
		if (o.has("linkVersion")) c.linkVersion = o.get("linkVersion").getAsLong();
		if (o.has("ui") && o.get("ui").isJsonObject()) {
			JsonObject ui = o.getAsJsonObject("ui");
			if (ui.has("hidden")) for (JsonElement e : ui.getAsJsonArray("hidden")) c.hidden.add(e.getAsString());
			if (ui.has("order")) {
				for (Map.Entry<String, JsonElement> e : ui.getAsJsonObject("order").entrySet()) {
					java.util.List<String> ids = new java.util.ArrayList<>();
					for (JsonElement id : e.getValue().getAsJsonArray()) ids.add(id.getAsString());
					c.order.put(e.getKey(), ids);
				}
			}
		}
		if (o.has("text")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("text").entrySet()) {
				c.texts.put(e.getKey().toLowerCase(), e.getValue().getAsString());
			}
		}
		if (o.has("values")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("values").entrySet()) {
				c.values.put(e.getKey().toLowerCase(), e.getValue().getAsDouble());
			}
		}
		if (o.has("collections") && o.get("collections").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("collections").entrySet()) {
				if (!e.getValue().isJsonArray()) continue;
				java.util.List<Row> rows = new java.util.ArrayList<>();
				for (JsonElement re : e.getValue().getAsJsonArray()) {
					if (!re.isJsonObject()) continue;
					Row row = new Row();
					for (Map.Entry<String, JsonElement> ce : re.getAsJsonObject().entrySet()) {
						JsonElement v = ce.getValue();
						String key = ce.getKey().toLowerCase();
						if (!v.isJsonPrimitive()) continue;
						if (v.getAsJsonPrimitive().isString()) row.texts.put(key, v.getAsString());
						else if (v.getAsJsonPrimitive().isBoolean()) row.values.put(key, v.getAsBoolean() ? 1.0 : 0.0);
						else row.values.put(key, v.getAsDouble());
					}
					rows.add(row);
				}
				c.collections.put(e.getKey().toLowerCase(), rows);
			}
		}
		return c;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("format", format);
		if (!kind.equals(SheetFormat.DEFAULT_KIND)) o.addProperty("kind", kind);
		if (!theme.isEmpty()) o.addProperty("theme", theme);
		if (!link.isEmpty()) {
			o.addProperty("link", link);
			o.addProperty("linkVersion", linkVersion);
		}
		JsonObject t = new JsonObject();
		new java.util.TreeMap<>(texts).forEach(t::addProperty);
		o.add("text", t);
		JsonObject v = new JsonObject();
		new java.util.TreeMap<>(values).forEach((k, d) -> {
			if (d == Math.rint(d) && Math.abs(d) < 1.0E12) v.addProperty(k, (long) (double) d);
			else v.addProperty(k, d);
		});
		o.add("values", v);
		boolean anyRows = false;
		for (java.util.List<Row> rows : collections.values()) if (!rows.isEmpty()) anyRows = true;
		if (anyRows) {
			JsonObject cs = new JsonObject();
			collections.forEach((id, rows) -> {
				if (rows.isEmpty()) return;
				com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
				for (Row r : rows) {
					JsonObject ro = new JsonObject();
					new java.util.TreeMap<>(r.texts).forEach(ro::addProperty);
					new java.util.TreeMap<>(r.values).forEach((k, d) -> {
						if (d == Math.rint(d) && Math.abs(d) < 1.0E12) ro.addProperty(k, (long) (double) d);
						else ro.addProperty(k, d);
					});
					arr.add(ro);
				}
				cs.add(id, arr);
			});
			o.add("collections", cs);
		}
		if (!hidden.isEmpty() || !order.isEmpty()) {
			JsonObject ui = new JsonObject();
			if (!hidden.isEmpty()) {
				com.google.gson.JsonArray a = new com.google.gson.JsonArray();
				hidden.forEach(a::add);
				ui.add("hidden", a);
			}
			if (!order.isEmpty()) {
				JsonObject ord = new JsonObject();
				order.forEach((k, ids) -> {
					com.google.gson.JsonArray a = new com.google.gson.JsonArray();
					ids.forEach(a::add);
					ord.add(k, a);
				});
				ui.add("order", ord);
			}
			o.add("ui", ui);
		}
		return o;
	}

	public String displayName() {
		return texts.getOrDefault("name", file);
	}
}
