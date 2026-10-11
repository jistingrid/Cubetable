package dev.tacticalcombat.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The player's own settings for the action bar, kept in {@code config/tacticalcombat/actionbar.json}: its size and
 * opacity, which parts it shows, where it sits, and the entries pinned from the trays (per character).
 */
final class ActionBarConfig {
	static final float[] SCALES = {0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f};

	static int scaleIndex = 2;
	static float opacity = 0.92f;
	static boolean bars = true;
	static boolean resources = true;
	static boolean movement = true;
	static boolean keys = true;
	/** Where the player dragged the bar: its centre and bottom edge in screen pixels; -1 = the default place. */
	static int cx = -1;
	static int bottom = -1;
	private static final Map<String, Set<String>> PINS = new HashMap<>();
	private static boolean loaded;

	private ActionBarConfig() {}

	static float scale() {
		return SCALES[Math.max(0, Math.min(SCALES.length - 1, scaleIndex))];
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("tacticalcombat").resolve("actionbar.json");
	}

	static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path f = file();
		if (!Files.isRegularFile(f)) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.has("scale")) scaleIndex = Math.max(0, Math.min(SCALES.length - 1, o.get("scale").getAsInt()));
			if (o.has("opacity")) opacity = Math.max(0.4f, Math.min(1f, o.get("opacity").getAsFloat()));
			if (o.has("bars")) bars = o.get("bars").getAsBoolean();
			if (o.has("resources")) resources = o.get("resources").getAsBoolean();
			if (o.has("movement")) movement = o.get("movement").getAsBoolean();
			if (o.has("keys")) keys = o.get("keys").getAsBoolean();
			if (o.has("x")) cx = o.get("x").getAsInt();
			if (o.has("bottom")) bottom = o.get("bottom").getAsInt();
			if (o.has("pins") && o.get("pins").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("pins").entrySet()) {
					Set<String> set = new LinkedHashSet<>();
					for (JsonElement p : e.getValue().getAsJsonArray()) set.add(p.getAsString());
					PINS.put(e.getKey(), set);
				}
			}
		} catch (Exception e) {
			TacticalCombatMod.LOGGER.warn("Could not read {}: {}", f, e.toString());
		}
	}

	static void save() {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("scale", scaleIndex);
			o.addProperty("opacity", opacity);
			o.addProperty("bars", bars);
			o.addProperty("resources", resources);
			o.addProperty("movement", movement);
			o.addProperty("keys", keys);
			o.addProperty("x", cx);
			o.addProperty("bottom", bottom);
			JsonObject pins = new JsonObject();
			for (Map.Entry<String, Set<String>> e : PINS.entrySet()) {
				if (e.getValue().isEmpty()) continue;
				JsonArray arr = new JsonArray();
				for (String s : e.getValue()) arr.add(s);
				pins.add(e.getKey(), arr);
			}
			o.add("pins", pins);
			Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			TacticalCombatMod.LOGGER.warn("Could not write the action bar settings: {}", e.toString());
		}
	}

	static boolean pinned(String character, String slot) {
		Set<String> set = PINS.get(character);
		return set != null && set.contains(slot);
	}

	static void togglePin(String character, String slot) {
		Set<String> set = PINS.computeIfAbsent(character, k -> new LinkedHashSet<>());
		if (!set.remove(slot)) set.add(slot);
		save();
	}

	/** Position in the pin list, so pinned entries keep the order they were pinned in. */
	static int pinOrder(String character, String slot) {
		Set<String> set = PINS.get(character);
		if (set == null) return Integer.MAX_VALUE;
		int i = 0;
		for (String s : set) {
			if (s.equals(slot)) return i;
			i++;
		}
		return Integer.MAX_VALUE;
	}

	static void resetLook() {
		scaleIndex = 2;
		opacity = 0.92f;
		bars = resources = movement = keys = true;
		cx = -1;
		bottom = -1;
		save();
	}
}
