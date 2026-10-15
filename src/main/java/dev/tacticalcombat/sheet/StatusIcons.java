package dev.tacticalcombat.sheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bundled status icons (from game-icons.net, see {@code textures/status/CREDITS.md}) and how a condition row finds its icon.
 * An icon is named by a short id such as {@code spider-web}; a pack stores that id in a column of type {@code icon}.
 * This class knows nothing about Minecraft, so it can be tested on its own.
 */
public final class StatusIcons {
	public static final String INDEX_PATH = "/assets/tacticalcombat/textures/status/index.json";

	public record Icon(String id, String name, String author, String license, String tags) {
		boolean matches(String[] words) {
			String hay = (name + " " + tags + " " + id).toLowerCase(Locale.ROOT);
			for (String w : words) if (!hay.contains(w)) return false;
			return true;
		}
	}

	private static Map<String, Icon> icons;

	private StatusIcons() {}

	public static synchronized Map<String, Icon> all() {
		if (icons == null) {
			try (InputStream in = StatusIcons.class.getResourceAsStream(INDEX_PATH)) {
				icons = in == null ? new LinkedHashMap<>() : parse(new InputStreamReader(in, StandardCharsets.UTF_8));
			} catch (Exception e) {
				icons = new LinkedHashMap<>();
			}
		}
		return icons;
	}

	/** Reads an {@code index.json} ({@code {"icons":[{id,name,author,license,tags[]}]}}). */
	public static Map<String, Icon> parse(java.io.Reader reader) {
		Map<String, Icon> out = new LinkedHashMap<>();
		JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
		JsonArray list = root.has("icons") ? root.getAsJsonArray("icons") : new JsonArray();
		for (JsonElement e : list) {
			JsonObject o = e.getAsJsonObject();
			String id = o.get("id").getAsString();
			StringBuilder tags = new StringBuilder();
			if (o.has("tags")) for (JsonElement t : o.getAsJsonArray("tags")) tags.append(tags.length() > 0 ? " " : "").append(t.getAsString());
			out.put(id, new Icon(id, o.has("name") ? o.get("name").getAsString() : id,
					o.has("author") ? o.get("author").getAsString() : "", o.has("license") ? o.get("license").getAsString() : "", tags.toString()));
		}
		return out;
	}

	public static Icon get(String id) {
		return id == null ? null : all().get(id.trim().toLowerCase(Locale.ROOT));
	}

	/** Icons whose name or tags contain every word of {@code query} (all of them when it is empty). */
	public static List<Icon> search(String query) {
		String[] words = query == null || query.isBlank() ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		List<Icon> out = new ArrayList<>();
		for (Icon i : all().values()) if (i.matches(words)) out.add(i);
		return out;
	}

	/** The first column of type {@code icon} in the table, or null. */
	public static SheetFormat.Col column(SheetFormat.Collection coll) {
		if (coll == null) return null;
		for (SheetFormat.Col c : coll.columns) if (c.isIcon()) return c;
		return null;
	}

	/**
	 * The icon id of a condition row: the one stored in its icon column, else the icon of the pack's preset with the same name
	 * (so conditions made before the column existed still get theirs), else "" (the HUD then shows a lettered badge).
	 */
	public static String iconOf(SheetFormat.Collection coll, CharacterData.Row row) {
		SheetFormat.Col col = column(coll);
		if (col == null) return "";
		String id = row.texts.getOrDefault(col.id, "").trim();
		if (get(id) != null) return get(id).id();
		SheetFormat.Col nameCol = coll.nameCol();
		if (nameCol == null) return "";
		String name = row.texts.getOrDefault(nameCol.id, "").trim();
		for (CharacterData.Row p : coll.presets) {
			if (p.texts.getOrDefault(nameCol.id, "").trim().equalsIgnoreCase(name)) {
				String pid = p.texts.getOrDefault(col.id, "").trim();
				if (get(pid) != null) return get(pid).id();
			}
		}
		return "";
	}
}
