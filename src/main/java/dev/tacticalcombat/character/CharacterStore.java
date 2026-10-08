package dev.tacticalcombat.character;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Every character of the group, kept by the server and saved with the world ({@code tacticalcombat/characters.json}).
 * It is the shared copy combat and the Dungeon Master read from. Owners edit their own; nothing is validated
 * (the group is trusted).
 */
public final class CharacterStore {
	/** @param json the sheet (CharacterData JSON) without its "version", which the store owns */
	public static final class Entry {
		public final String id;
		public final UUID owner;
		public String ownerName;
		public long version;
		public JsonObject json;
		/** Who besides the owner and the DMs may see it: "" = nobody, "*" = everyone, otherwise comma-separated player uuids. */
		public String share = "";

		/** Owners and Dungeon Masters always see it; others only when the DM shared it with them. */
		public boolean visibleTo(UUID player, boolean dm) {
			if (dm || owner.equals(player)) return true;
			if (share.equals("*")) return true;
			for (String s : share.split(",")) if (s.trim().equals(player.toString())) return true;
			return false;
		}

		public Entry(String id, UUID owner, String ownerName, long version, JsonObject json) {
			this.id = id;
			this.owner = owner;
			this.ownerName = ownerName;
			this.version = version;
			this.json = json;
		}
	}

	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
	private static Path file;

	private CharacterStore() {}

	public static Path folder(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("tacticalcombat");
	}

	public static void load(MinecraftServer server) {
		ENTRIES.clear();
		file = folder(server).resolve("characters.json");
		if (!Files.isRegularFile(file)) return;
		try {
			JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
			for (JsonElement e : arr) {
				JsonObject o = e.getAsJsonObject();
				Entry en = new Entry(o.get("id").getAsString(), UUID.fromString(o.get("owner").getAsString()),
						o.get("ownerName").getAsString(), o.get("version").getAsLong(), o.getAsJsonObject("data"));
				if (o.has("share")) en.share = o.get("share").getAsString();
				ENTRIES.put(en.id, en);
			}
		} catch (Exception ex) {
			TacticalCombatMod.LOGGER.error("Could not read {}: {}", file, ex.toString());
		}
	}

	public static void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			JsonArray arr = new JsonArray();
			for (Entry en : ENTRIES.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", en.id);
				o.addProperty("owner", en.owner.toString());
				o.addProperty("ownerName", en.ownerName);
				o.addProperty("version", en.version);
				if (!en.share.isEmpty()) o.addProperty("share", en.share);
				o.add("data", en.json);
				arr.add(o);
			}
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(arr), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			TacticalCombatMod.LOGGER.error("Could not write {}: {}", file, ex.toString());
		}
	}

	public static void clear() {
		ENTRIES.clear();
		file = null;
	}

	public static Entry get(String id) {
		return ENTRIES.get(id);
	}

	public static List<Entry> all() {
		return new ArrayList<>(ENTRIES.values());
	}

	public static void put(Entry e) {
		ENTRIES.put(e.id, e);
	}

	public static Entry remove(String id) {
		return ENTRIES.remove(id);
	}
}
