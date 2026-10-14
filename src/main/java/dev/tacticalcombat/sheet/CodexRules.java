package dev.tacticalcombat.sheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a game pack presents its codex: the {@code "codex"} block of a format. The engine knows nothing about any
 * game's creatures; the pack says what its entry types are called, which sheet layout they use and which numbers the
 * list shows beside each name.
 *
 * <pre>
 * "codex": {
 *   "monsters": {
 *     "label": "Bestiary",
 *     "kind": "npc",
 *     "columns": [ { "label": "CR", "text": "cr" }, { "label": "HP", "value": "hp_max" }, { "label": "AC", "value": "ac" } ]
 *   }
 * }
 * </pre>
 */
public final class CodexRules {
	/** The entry types the codex has today: each is a folder under a pack's {@code codex/} folder. */
	public static final String MONSTERS = "monsters";
	public static final List<String> TYPES = List.of(MONSTERS);

	/** One figure shown beside an entry: a formula over the sheet ({@code value}) or one of its texts ({@code text}). */
	public record Column(String label, String value, String text, boolean signed) {}

	/** One entry type of one game. */
	public static final class Type {
		public String id = "";
		/** What the game calls the type ("Bestiary", "Creatures"). */
		public String label = "";
		/** The sheet layout entries of this type use; empty = the format's NPC layout when it has one, else its first. */
		public String kind = "";
		public final List<Column> columns = new ArrayList<>();
	}

	private CodexRules() {}

	/** What a type is called and shows when the pack says nothing about it. */
	public static Type defaults(String id) {
		Type t = new Type();
		t.id = id;
		t.label = id.isEmpty() ? "Entries" : Character.toUpperCase(id.charAt(0)) + id.substring(1);
		return t;
	}

	public static Type parse(String id, JsonObject o) {
		Type t = defaults(id);
		if (o.has("label") && !o.get("label").isJsonNull()) {
			String label = o.get("label").getAsString().trim();
			if (!label.isEmpty()) t.label = label;
		}
		if (o.has("kind") && !o.get("kind").isJsonNull()) t.kind = o.get("kind").getAsString().trim().toLowerCase(Locale.ROOT);
		if (o.has("columns") && o.get("columns").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("columns")) {
				if (!e.isJsonObject()) throw new IllegalArgumentException("codex." + id + ": a column must be an object");
				JsonObject co = e.getAsJsonObject();
				String label = co.has("label") ? co.get("label").getAsString() : "";
				String value = co.has("value") && !co.get("value").isJsonNull() ? co.get("value").getAsString() : null;
				String text = co.has("text") && !co.get("text").isJsonNull() ? co.get("text").getAsString().toLowerCase(Locale.ROOT) : null;
				if (label.isBlank()) throw new IllegalArgumentException("codex." + id + ": a column has no \"label\"");
				if (value == null && text == null) {
					throw new IllegalArgumentException("codex." + id + ": column '" + label + "' needs a \"value\" (formula) or a \"text\" (name of a text)");
				}
				boolean signed = co.has("signed") && co.get("signed").getAsBoolean();
				t.columns.add(new Column(label, value, text, signed));
			}
		}
		return t;
	}
}
