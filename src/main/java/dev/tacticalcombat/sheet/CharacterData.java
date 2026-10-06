package dev.tacticalcombat.sheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** One saved character: which format it uses plus its raw numbers and text. The format does the maths. */
public final class CharacterData {
	public String file = "";
	public String format = "";
	public final Map<String, String> texts = new HashMap<>();
	public final Map<String, Double> values = new HashMap<>();

	public static CharacterData parse(JsonObject o, String file) {
		CharacterData c = new CharacterData();
		c.file = file;
		c.format = o.has("format") ? o.get("format").getAsString() : "";
		if (c.format.isBlank()) throw new IllegalArgumentException("missing \"format\"");
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
		return c;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("format", format);
		JsonObject t = new JsonObject();
		new java.util.TreeMap<>(texts).forEach(t::addProperty);
		o.add("text", t);
		JsonObject v = new JsonObject();
		new java.util.TreeMap<>(values).forEach((k, d) -> {
			if (d == Math.rint(d) && Math.abs(d) < 1.0E12) v.addProperty(k, (long) (double) d);
			else v.addProperty(k, d);
		});
		o.add("values", v);
		return o;
	}

	public String displayName() {
		return texts.getOrDefault("name", file);
	}
}
