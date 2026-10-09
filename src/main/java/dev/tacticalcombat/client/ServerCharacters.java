package dev.tacticalcombat.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.character.Gz;
import dev.tacticalcombat.net.ActiveActorPayload;
import dev.tacticalcombat.net.ActiveActorStatePayload;
import dev.tacticalcombat.net.CharacterDeletePayload;
import dev.tacticalcombat.net.CharacterHidePayload;
import dev.tacticalcombat.net.CharacterSharePayload;
import dev.tacticalcombat.net.CharacterPushPayload;
import dev.tacticalcombat.net.CharacterRemovePayload;
import dev.tacticalcombat.net.CharacterUpdatePayload;
import dev.tacticalcombat.net.RolePayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client's side of the shared characters. Characters you "link" are kept current on the server after every
 * save; other players' characters arrive here (read-only, unless you are a Dungeon Master); changes made by the
 * server or by a DM to one of yours are written back to your own file.
 */
public final class ServerCharacters {
	private static final int MAX_PACKET = 31000;

	/** Characters of other players, by server id. */
	private static final Map<String, CharacterData> REMOTE = new LinkedHashMap<>();
	/** What the server last said about each id (normalised JSON), to tell real changes from echoes. */
	private static final Map<String, JsonObject> SERVER_JSON = new HashMap<>();
	private static final Map<String, Long> SERVER_VERSION = new HashMap<>();
	private static final Set<String> KNOWN = new HashSet<>();
	/** Who may see each character besides its owner and the DMs ("" nobody, "*" everyone, else uuids). */
	private static final Map<String, String> SHARE = new HashMap<>();
	private static boolean dm;
	/** Server id of the character this player's model stands for ("" = none). */
	private static String activeId = "";

	private ServerCharacters() {}

	public static void init() {
		SheetLibrary.onSaved = c -> {
			if (!c.link.isEmpty() && !c.remote) push(c);
		};
		SheetLibrary.onDeleted = c -> {
			if (!c.link.isEmpty() && !c.remote) sendDelete(c.link);
		};
	}

	public static boolean available() {
		return ClientPlayNetworking.canSend(CharacterPushPayload.ID);
	}

	public static boolean isActive(CharacterData c) {
		return !c.link.isEmpty() && !c.remote && c.link.equals(activeId);
	}

	/** Makes one of your linked characters the Active Actor, or clears it when it already is. */
	public static void toggleActive(CharacterData c) {
		if (c.link.isEmpty() || c.remote || !available()) return;
		ClientPlayNetworking.send(new ActiveActorPayload(isActive(c) ? "" : c.link));
	}

	public static void receiveActor(ActiveActorStatePayload p) {
		activeId = p.id();
	}

	public static boolean isDm() {
		return dm;
	}

	public static void clear() {
		REMOTE.clear();
		SERVER_JSON.clear();
		SERVER_VERSION.clear();
		KNOWN.clear();
		SHARE.clear();
		activeId = "";
		dm = false;
	}

	/** Other players' characters, ordered by owner then name. */
	public static List<CharacterData> remotes() {
		List<CharacterData> list = new ArrayList<>(REMOTE.values());
		list.sort((a, b) -> {
			int c = a.ownerName.compareToIgnoreCase(b.ownerName);
			return c != 0 ? c : a.displayName().compareToIgnoreCase(b.displayName());
		});
		return list;
	}

	public static String shareOf(String id) {
		return SHARE.getOrDefault(id, "");
	}

	/** DM: decide who may see a character ("" nobody, "*" everyone, else comma-separated player uuids). */
	public static void setShare(String id, String share) {
		if (!dm || id.isEmpty() || !available()) return;
		SHARE.put(id, share);
		ClientPlayNetworking.send(new CharacterSharePayload(id, share));
	}

	// ------------------------------------------------------------------ sending

	private static JsonObject forServer(CharacterData c) {
		JsonObject o = c.toJson();
		o.remove("link");
		o.remove("linkVersion");
		return o;
	}

	/** Sends the character's current state. Anyone may push their own; a DM may push anyone's. */
	public static void push(CharacterData c) {
		if (c.link.isEmpty() || !available()) return;
		byte[] data = Gz.pack(forServer(c).toString());
		if (data.length > MAX_PACKET) {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player != null) {
				mc.player.sendMessage(net.minecraft.text.Text.literal("[Sheet] " + c.displayName()
						+ " is too large to share with the server (over about 30 KB compressed).").formatted(net.minecraft.util.Formatting.RED), false);
			}
			return;
		}
		ClientPlayNetworking.send(new CharacterPushPayload(c.link, data));
	}

	private static void sendDelete(String id) {
		if (available()) ClientPlayNetworking.send(new CharacterDeletePayload(id));
	}

	/** Starts sharing one of your characters with the server (and so with the DM and the group). */
	public static void link(CharacterData c) throws IOException {
		if (!c.link.isEmpty() || c.remote) return;
		c.link = java.util.UUID.randomUUID().toString();
		c.linkVersion = 0;
		SheetLibrary.save(c); // the save hook sends it
	}

	/** Stops sharing: the server's copy is deleted, your file stays. */
	public static void unlink(CharacterData c) throws IOException {
		if (c.link.isEmpty() || c.remote) return;
		sendDelete(c.link);
		KNOWN.remove(c.link);
		SERVER_JSON.remove(c.link);
		c.link = "";
		c.linkVersion = 0;
		SheetLibrary.quiet = true;
		try {
			SheetLibrary.save(c);
		} finally {
			SheetLibrary.quiet = false;
		}
	}

	/** DM: delete someone else's character from the server. */
	public static void deleteRemote(CharacterData c) {
		if (!c.remote || !dm) return;
		sendDelete(c.link);
	}

	/** After a refused change to a character that is not yours: show what the server has again. */
	public static void revert(CharacterData c) {
		JsonObject json = SERVER_JSON.get(c.link);
		if (json == null) return;
		REMOTE.put(c.link, build(c.link, json, c.ownerName, SERVER_VERSION.getOrDefault(c.link, c.linkVersion), true));
	}

	/** DM edits: show the change at once; the server's echo confirms it. */
	public static void replaceRemote(CharacterData c) {
		if (c.remote && !c.link.isEmpty()) REMOTE.put(c.link, c);
	}

	// ------------------------------------------------------------------ receiving

	private static CharacterData build(String id, JsonObject json, String ownerName, long version, boolean remote) {
		JsonObject copy = json.deepCopy();
		CharacterData c = CharacterData.parse(copy, "");
		c.link = id;
		c.linkVersion = version;
		c.remote = remote;
		c.ownerName = ownerName;
		return c;
	}

	private static JsonObject normalised(JsonObject o) {
		JsonObject c = o.deepCopy();
		c.remove("id");
		c.remove("version");
		c.remove("link");
		c.remove("linkVersion");
		return c;
	}

	public static void receiveUpdate(CharacterUpdatePayload p) {
		MinecraftClient mc = MinecraftClient.getInstance();
		JsonObject json;
		try {
			json = JsonParser.parseString(Gz.unpack(p.data())).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			return;
		}
		JsonObject norm = normalised(json);
		KNOWN.add(p.id());
		SHARE.put(p.id(), p.share());
		SERVER_JSON.put(p.id(), norm);
		SERVER_VERSION.put(p.id(), p.version());

		boolean mine = mc.player != null && p.ownerUuid().equals(mc.player.getUuidAsString());
		if (!mine) {
			REMOTE.put(p.id(), build(p.id(), json, p.ownerName(), p.version(), true));
			return;
		}
		// one of yours: keep your file in step with the server's copy
		REMOTE.remove(p.id());
		CharacterData local = null;
		for (CharacterData c : SheetLibrary.CHARACTERS) {
			if (p.id().equals(c.link)) {
				local = c;
				break;
			}
		}
		SheetLibrary.quiet = true;
		try {
			if (local == null) {
				CharacterData c = build(p.id(), json, p.ownerName(), p.version(), false);
				SheetLibrary.save(c);
			} else if (p.version() > local.linkVersion) {
				if (normalised(local.toJson()).equals(norm)) {
					local.linkVersion = p.version(); // our own change coming back
				} else {
					CharacterData c = build(p.id(), json, p.ownerName(), p.version(), false);
					c.file = local.file;
					SheetLibrary.save(c);
				}
			}
		} catch (IOException e) {
			// the file could not be written; the next save will try again
		} finally {
			SheetLibrary.quiet = false;
		}
	}

	/** The character still exists but is not shared with you (any more). */
	public static void receiveHide(CharacterHidePayload p) {
		REMOTE.remove(p.id());
		SERVER_JSON.remove(p.id());
		SERVER_VERSION.remove(p.id());
		SHARE.remove(p.id());
	}

	public static void receiveRemove(CharacterRemovePayload p) {
		SHARE.remove(p.id());
		REMOTE.remove(p.id());
		SERVER_JSON.remove(p.id());
		SERVER_VERSION.remove(p.id());
		KNOWN.remove(p.id());
		for (CharacterData c : SheetLibrary.CHARACTERS) {
			if (p.id().equals(c.link)) {
				c.link = "";
				c.linkVersion = 0;
				SheetLibrary.quiet = true;
				try {
					SheetLibrary.save(c);
				} catch (IOException e) {
					// keep going
				} finally {
					SheetLibrary.quiet = false;
				}
				break;
			}
		}
	}

	/** The last message of the sync after joining (and again when your role changes). */
	public static void receiveRole(RolePayload p) {
		dm = p.dm();
		// characters you shared that this server does not know (a new world), or that you changed while away
		for (CharacterData c : new ArrayList<>(SheetLibrary.CHARACTERS)) {
			if (c.link.isEmpty()) continue;
			if (!KNOWN.contains(c.link)) {
				push(c);
			} else if (SERVER_VERSION.getOrDefault(c.link, 0L) <= c.linkVersion
					&& !normalised(c.toJson()).equals(SERVER_JSON.get(c.link))) {
				push(c);
			}
		}
	}
}
