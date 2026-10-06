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

	public String displayName() {
		return texts.getOrDefault("name", file);
	}
}
