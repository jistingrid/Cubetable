package dev.tacticalcombat.character;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.actor.ActorRecord;
import dev.tacticalcombat.actor.ActorRegistry;
import dev.tacticalcombat.codex.CodexKeys;
import dev.tacticalcombat.combat.DmTools;
import dev.tacticalcombat.net.CodexActionPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The server's side of the codex. An entry the Dungeon Master adds becomes a sheet held by the server (no player owns
 * it, so it stays out of the sheet lists, like an Actor's private sheet) that only Dungeon Masters are sent. The
 * Actors tab offers it as a template: Actors made from it get their own copy. Its id comes from the entry's key, so
 * adding the same entry again updates it instead of making a second one.
 */
public final class CodexService {
	/** What the server shows as the owner of a codex sheet; the client tells these sheets from Actors' by it. */
	public static final String OWNER_NAME = "Codex";
	private static final UUID SERVER = new UUID(0L, 0L);

	private CodexService() {}

	public static void handle(ServerPlayerEntity dm, CodexActionPayload p) {
		MinecraftServer server = dm.getServer();
		if (server == null || !Roles.isDm(dm.getUuid())) return;
		String error = switch (p.op()) {
			case CodexActionPayload.ADD -> add(server, p.key(), p.data());
			case CodexActionPayload.REMOVE -> remove(server, p.key());
			default -> "Unknown codex action.";
		};
		if (error != null) dm.sendMessage(Text.literal("[Codex] " + error), false);
		DmTools.broadcast(server);
	}

	/** Returns null when it worked, else a sentence saying why not. */
	public static String add(MinecraftServer server, String key, byte[] data) {
		if (!CodexKeys.valid(key)) return "That is not a codex entry.";
		JsonObject json;
		try {
			json = JsonParser.parseString(Gz.unpack(data)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			return "The server could not read the entry.";
		}
		String format = json.has("format") && json.get("format").isJsonPrimitive() ? json.get("format").getAsString() : "";
		if (!format.equals(CodexKeys.formatOf(key))) return "The entry's game does not match its name in the codex.";
		String name = json.has("text") && json.get("text").isJsonObject() && json.getAsJsonObject("text").has("name")
				? json.getAsJsonObject("text").get("name").getAsString().trim() : "";
		if (name.isEmpty() || !key.endsWith("/" + CodexKeys.slug(name))) return "The entry has no name, or not the one it is filed under.";
		for (String k : new String[] {"id", "version", "link", "linkVersion", "codex"}) json.remove(k);

		String id = CodexKeys.idOf(key);
		json.addProperty("id", id); // like every sheet the server holds
		CharacterStore.Entry existing = CharacterStore.get(id);
		if (existing == null) {
			CharacterStore.Entry created = new CharacterStore.Entry(id, SERVER, OWNER_NAME, 1, json);
			CharacterStore.put(created);
			CharacterStore.save();
			CharacterSync.broadcast(server, created, "");
			return null;
		}
		if (!isCodexSheet(existing)) return "That id is already used by another sheet.";
		if (existing.json.equals(json)) return null; // nothing new
		existing.json = json;
		CharacterSync.changedByServer(server, existing); // new version, saved and sent
		return null;
	}

	/** Returns null when it worked (or the entry was not there), else a sentence saying why not. */
	public static String remove(MinecraftServer server, String key) {
		if (!CodexKeys.valid(key)) return "That is not a codex entry.";
		String id = CodexKeys.idOf(key);
		CharacterStore.Entry existing = CharacterStore.get(id);
		if (existing == null) return null;
		if (!isCodexSheet(existing)) return "That sheet is not a codex entry.";
		List<String> users = new ArrayList<>();
		for (ActorRecord r : ActorRegistry.all()) if (!r.ownsSheet && id.equals(r.sheetId)) users.add(r.name);
		if (!users.isEmpty()) {
			String shown = String.join(", ", users.subList(0, Math.min(3, users.size())));
			return "It is still the shared sheet of " + shown + (users.size() > 3 ? " and " + (users.size() - 3) + " more" : "")
					+ ". Give them another sheet or delete them first.";
		}
		CharacterSync.removeByServer(server, id);
		return null;
	}

	/** True for a sheet the codex put there (held by the server, owner name "Codex"). */
	public static boolean isCodexSheet(CharacterStore.Entry e) {
		return SERVER.equals(e.owner) && OWNER_NAME.equals(e.ownerName);
	}
}
