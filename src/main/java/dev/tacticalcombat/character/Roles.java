package dev.tacticalcombat.character;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Who is a Dungeon Master (saved with the world, {@code tacticalcombat/dms.json}). A DM can read and edit every sheet. */
public final class Roles {
	private static final Set<UUID> DMS = new LinkedHashSet<>();
	private static Path file;

	private Roles() {}

	public static void load(MinecraftServer server) {
		DMS.clear();
		file = CharacterStore.folder(server).resolve("dms.json");
		if (!Files.isRegularFile(file)) return;
		try {
			for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
				DMS.add(UUID.fromString(e.getAsString()));
			}
		} catch (Exception ex) {
			TacticalCombatMod.LOGGER.error("Could not read {}: {}", file, ex.toString());
		}
	}

	private static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			JsonArray arr = new JsonArray();
			DMS.forEach(u -> arr.add(u.toString()));
			Files.writeString(file, arr.toString(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			TacticalCombatMod.LOGGER.error("Could not write {}: {}", file, ex.toString());
		}
	}

	public static void clear() {
		DMS.clear();
		file = null;
	}

	public static boolean isDm(UUID player) {
		return DMS.contains(player);
	}

	public static boolean add(UUID player) {
		boolean changed = DMS.add(player);
		if (changed) save();
		return changed;
	}

	public static boolean remove(UUID player) {
		boolean changed = DMS.remove(player);
		if (changed) save();
		return changed;
	}

	public static Set<UUID> all() {
		return Set.copyOf(DMS);
	}
}
