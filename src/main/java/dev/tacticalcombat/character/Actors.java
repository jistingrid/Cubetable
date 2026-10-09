package dev.tacticalcombat.character;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which character each player's model currently stands for (the "Active Actor"), saved with the world
 * ({@code tacticalcombat/actors.json}). Combat reads its numbers from this character.
 */
public final class Actors {
	private static final Map<UUID, String> ACTIVE = new HashMap<>();
	private static Path file;

	private Actors() {}

	public static void load(MinecraftServer server) {
		ACTIVE.clear();
		file = CharacterStore.folder(server).resolve("actors.json");
		if (!Files.isRegularFile(file)) return;
		try {
			for (var e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
				ACTIVE.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
			}
		} catch (Exception ex) {
			TacticalCombatMod.LOGGER.error("Could not read {}: {}", file, ex.toString());
		}
	}

	private static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			JsonObject o = new JsonObject();
			ACTIVE.forEach((u, id) -> o.addProperty(u.toString(), id));
			Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			TacticalCombatMod.LOGGER.error("Could not write {}: {}", file, ex.toString());
		}
	}

	public static void clear() {
		ACTIVE.clear();
		file = null;
	}

	/** Server id of the player's active character, or "" when none is chosen. */
	public static String get(UUID player) {
		return ACTIVE.getOrDefault(player, "");
	}

	/** The entry itself, only if it still exists and belongs to the player. */
	public static CharacterStore.Entry entryOf(UUID player) {
		String id = get(player);
		if (id.isEmpty()) return null;
		CharacterStore.Entry e = CharacterStore.get(id);
		return e != null && e.owner.equals(player) ? e : null;
	}

	public static void set(UUID player, String id) {
		if (id.isEmpty()) ACTIVE.remove(player);
		else ACTIVE.put(player, id);
		save();
	}

	/** A character was deleted: whoever had it active has none now. Returns those players. */
	public static java.util.List<UUID> forget(String id) {
		java.util.List<UUID> out = new java.util.ArrayList<>();
		for (var it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getValue().equals(id)) {
				out.add(e.getKey());
				it.remove();
			}
		}
		if (!out.isEmpty()) save();
		return out;
	}
}
