package dev.tacticalcombat.actor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.character.CharacterStore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Every Actor of the world, saved with it ({@code tacticalcombat/scene_actors.json}), and the lookups combat needs. */
public final class ActorRegistry {
	/** Command tag on every Actor body: the id of the record it belongs to. */
	public static final String TAG = "tbc_actor:";

	private static final Map<String, ActorRecord> RECORDS = new LinkedHashMap<>();
	/** Folders that exist even when empty (a folder that Actors use exists by itself). */
	private static final List<String> FOLDERS = new ArrayList<>();
	private static Path file;

	private ActorRegistry() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ActorRegistry::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			RECORDS.clear();
			FOLDERS.clear();
			file = null;
		});
	}

	private static void load(MinecraftServer server) {
		RECORDS.clear();
		FOLDERS.clear();
		file = CharacterStore.folder(server).resolve("scene_actors.json");
		if (!Files.isRegularFile(file)) return;
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			JsonArray list = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("actors"); // the first version was a bare list
			if (root.isJsonObject() && root.getAsJsonObject().has("folders")) {
				for (JsonElement f : root.getAsJsonObject().getAsJsonArray("folders")) FOLDERS.add(f.getAsString());
			}
			for (JsonElement el : list) {
				JsonObject o = el.getAsJsonObject();
				ActorRecord r = new ActorRecord(o.get("id").getAsString(), o.get("name").getAsString());
				if (o.has("sheet")) r.sheetId = o.get("sheet").getAsString();
				if (o.has("owns")) r.ownsSheet = o.get("owns").getAsBoolean();
				if (o.has("kind")) r.kind = o.get("kind").getAsString();
				if (o.has("value")) r.value = o.get("value").getAsString();
				if (o.has("disposition")) r.disposition = o.get("disposition").getAsInt();
				if (o.has("dm")) r.dmControl = o.get("dm").getAsBoolean();
				if (o.has("body")) r.entityUuid = UUID.fromString(o.get("body").getAsString());
				if (o.has("folder")) r.folder = o.get("folder").getAsString();
				if (o.has("tags")) for (JsonElement t : o.getAsJsonArray("tags")) r.tags.add(t.getAsString());
				RECORDS.put(r.id, r);
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
			for (ActorRecord r : RECORDS.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", r.id);
				o.addProperty("name", r.name);
				o.addProperty("sheet", r.sheetId);
				o.addProperty("owns", r.ownsSheet);
				o.addProperty("kind", r.kind);
				o.addProperty("value", r.value);
				o.addProperty("disposition", r.disposition);
				o.addProperty("dm", r.dmControl);
				if (r.entityUuid != null) o.addProperty("body", r.entityUuid.toString());
				if (!r.folder.isEmpty()) o.addProperty("folder", r.folder);
				if (!r.tags.isEmpty()) {
					JsonArray tags = new JsonArray();
					for (String t : r.tags) tags.add(t);
					o.add("tags", tags);
				}
				arr.add(o);
			}
			JsonObject root = new JsonObject();
			JsonArray folders = new JsonArray();
			for (String f : FOLDERS) folders.add(f);
			root.add("folders", folders);
			root.add("actors", arr);
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			TacticalCombatMod.LOGGER.error("Could not write {}: {}", file, ex.toString());
		}
	}

	// ---------------------------------------------------------------- records

	public static List<ActorRecord> all() {
		return new ArrayList<>(RECORDS.values());
	}

	public static ActorRecord get(String id) {
		return RECORDS.get(id);
	}

	public static void put(ActorRecord r) {
		RECORDS.put(r.id, r);
		save();
	}

	public static void remove(String id) {
		RECORDS.remove(id);
		save();
	}

	/** By id, else by name (ignoring case). */
	public static ActorRecord find(String idOrName) {
		ActorRecord r = RECORDS.get(idOrName);
		if (r != null) return r;
		for (ActorRecord x : RECORDS.values()) if (x.name.equalsIgnoreCase(idOrName)) return x;
		return null;
	}

	public static boolean nameTaken(String name) {
		for (ActorRecord x : RECORDS.values()) if (x.name.equalsIgnoreCase(name)) return true;
		return false;
	}

	/** "Wolf" -> "Wolf 2" -> "Wolf 3" ...: the first free name. */
	public static String freeName(String base) {
		String stem = base.replaceAll("\\s+\\d+$", "").trim();
		if (stem.isEmpty()) stem = "Actor";
		if (!nameTaken(stem)) return stem;
		for (int i = 2; i < 1000; i++) if (!nameTaken(stem + " " + i)) return stem + " " + i;
		return stem + " " + UUID.randomUUID().toString().substring(0, 4);
	}

	// ---------------------------------------------------------------- folders and tags

	/** Every folder: the ones made on purpose plus any an Actor is filed in, alphabetical. */
	public static List<String> folders() {
		List<String> out = new ArrayList<>(FOLDERS);
		for (ActorRecord r : RECORDS.values()) {
			if (!r.folder.isEmpty() && out.stream().noneMatch(f -> f.equalsIgnoreCase(r.folder))) out.add(r.folder);
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** The folder's spelling as stored, or null when there is none of that name. */
	public static String folderNamed(String name) {
		if (name == null) return null;
		for (String f : folders()) if (f.equalsIgnoreCase(name.trim())) return f;
		return null;
	}

	/** Makes the folder if needed. Returns its stored spelling, or null when the name is not usable. */
	public static String ensureFolder(String name) {
		name = cleanFolder(name);
		if (name == null) return null;
		String have = folderNamed(name);
		if (have != null) return have;
		FOLDERS.add(name);
		save();
		return name;
	}

	/** Trimmed, 1 to 32 characters, or null. */
	public static String cleanFolder(String name) {
		if (name == null) return null;
		name = name.trim();
		return name.isEmpty() || name.length() > 32 ? null : name;
	}

	public static boolean renameFolder(String from, String to) {
		String old = folderNamed(from);
		to = cleanFolder(to);
		if (old == null || to == null) return false;
		String clash = folderNamed(to);
		if (clash != null && !clash.equalsIgnoreCase(old)) return false;
		FOLDERS.removeIf(f -> f.equalsIgnoreCase(old));
		FOLDERS.add(to);
		for (ActorRecord r : RECORDS.values()) if (r.folder.equalsIgnoreCase(old)) r.folder = to;
		save();
		return true;
	}

	/** Deletes the folder; the Actors in it are not deleted, they go back to the top level. */
	public static void deleteFolder(String name) {
		String old = folderNamed(name);
		if (old == null) return;
		FOLDERS.removeIf(f -> f.equalsIgnoreCase(old));
		for (ActorRecord r : RECORDS.values()) if (r.folder.equalsIgnoreCase(old)) r.folder = "";
		save();
	}

	/** "Undead, act 2,#boss" -> [undead, act 2, boss]: trimmed, no duplicates (ignoring case), at most 12, 20 characters each. */
	public static List<String> parseTags(String csv) {
		List<String> out = new ArrayList<>();
		if (csv == null) return out;
		for (String part : csv.split(",")) {
			String t = part.trim();
			while (t.startsWith("#")) t = t.substring(1).trim();
			if (t.isEmpty()) continue;
			if (t.length() > 20) t = t.substring(0, 20);
			final String tag = t;
			if (out.stream().noneMatch(x -> x.equalsIgnoreCase(tag)) && out.size() < 12) out.add(tag);
		}
		return out;
	}

	public static String joinTags(List<String> tags) {
		return String.join(",", tags);
	}

	// ---------------------------------------------------------------- bodies

	/** The record a world entity is the body of, or null when it is not an Actor. */
	public static ActorRecord recordOf(Entity e) {
		if (e == null) return null;
		for (String tag : e.getCommandTags()) {
			if (tag.startsWith(TAG)) return RECORDS.get(tag.substring(TAG.length()));
		}
		return null;
	}

	/** The body of an Actor if it is placed and its chunk is loaded. */
	public static LivingEntity bodyOf(MinecraftServer server, ActorRecord r) {
		if (r.entityUuid == null) return null;
		for (ServerWorld w : server.getWorlds()) {
			Entity e = w.getEntity(r.entityUuid);
			if (e instanceof LivingEntity le && le.isAlive()) return le;
		}
		return null;
	}

	/** The server character that is this Actor's sheet, or null. */
	public static CharacterStore.Entry sheetOf(ActorRecord r) {
		return r == null || r.sheetId.isEmpty() ? null : CharacterStore.get(r.sheetId);
	}

	public static CharacterStore.Entry sheetEntryOf(Entity body) {
		return sheetOf(recordOf(body));
	}

	public static boolean isActorBody(Entity e) {
		return recordOf(e) != null;
	}

	/** A hostile Actor joins the fight when an encounter starts near it. */
	public static boolean isHostileBody(Entity e) {
		ActorRecord r = recordOf(e);
		return r != null && r.disposition == ActorRecord.HOSTILE;
	}

	/** The Dungeon Master walks this creature (a per-Actor switch; the global Auto movement switch can also take over). */
	public static boolean dmControlled(Entity e) {
		ActorRecord r = recordOf(e);
		return r != null && r.dmControl;
	}

	/** A character's display name from its JSON ("" when it has none). */
	public static String nameOfSheet(CharacterStore.Entry e) {
		if (e == null) return "";
		JsonObject t = e.json.has("text") && e.json.get("text").isJsonObject() ? e.json.getAsJsonObject("text") : null;
		return t != null && t.has("name") ? t.get("name").getAsString() : "";
	}

	/** The character whose name is {@code name} (ignoring case), or null. */
	public static CharacterStore.Entry sheetNamed(String name) {
		String want = name.toLowerCase(Locale.ROOT).trim();
		for (CharacterStore.Entry e : CharacterStore.all()) {
			if (nameOfSheet(e).toLowerCase(Locale.ROOT).trim().equals(want)) return e;
		}
		return null;
	}
}
