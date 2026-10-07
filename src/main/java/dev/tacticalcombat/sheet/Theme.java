package dev.tacticalcombat.sheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Window colours. A theme file lists only the colours it changes; the rest come from the default (crimson) palette.
 * Keys: bg, panel, panel_hover, edge, accent, muted, dim, banner_a, banner_b, roll_bg, roll_edge.
 */
public final class Theme {
	public static final String DEFAULT_ID = "crimson";

	private static final Map<String, Integer> BASE = new HashMap<>();

	static {
		BASE.put("bg", 0xFF181B20);
		BASE.put("panel", 0xFF20242B);
		BASE.put("panel_hover", 0xFF2B313A);
		BASE.put("edge", 0xFF2C313A);
		BASE.put("accent", 0xFFE0B84C);
		BASE.put("muted", 0xFF9AA0A8);
		BASE.put("dim", 0xFF6F7680);
		BASE.put("banner_a", 0xFF5C0F1B);
		BASE.put("banner_b", 0xFF8F1D2C);
		BASE.put("roll_bg", 0xFF2A2218);
		BASE.put("roll_edge", 0xFF7A6330);
	}

	public final String id;
	public final String name;
	private final Map<String, Integer> colors = new HashMap<>();

	public Theme(String id, String name) {
		this.id = id;
		this.name = name;
	}

	public int get(String key) {
		Integer c = colors.get(key);
		if (c == null) c = BASE.get(key);
		return c == null ? 0xFFFF00FF : c;
	}

	public static Theme parse(JsonObject o, String fallbackId) {
		String id = o.has("id") ? o.get("id").getAsString().toLowerCase(Locale.ROOT) : fallbackId;
		Theme t = new Theme(id, o.has("name") ? o.get("name").getAsString() : id);
		if (o.has("colors")) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("colors").entrySet()) {
				String hex = e.getValue().getAsString().replace("#", "");
				t.colors.put(e.getKey().toLowerCase(Locale.ROOT), 0xFF000000 | Integer.parseInt(hex, 16));
			}
		}
		return t;
	}

	public static Theme fallback() {
		return new Theme(DEFAULT_ID, "Crimson");
	}
}
