package dev.tacticalcombat.character;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.net.CharacterDeletePayload;
import dev.tacticalcombat.net.ActiveActorPayload;
import dev.tacticalcombat.net.ActiveActorStatePayload;
import dev.tacticalcombat.net.CharacterHidePayload;
import dev.tacticalcombat.net.CharacterPushPayload;
import dev.tacticalcombat.net.CharacterSharePayload;
import dev.tacticalcombat.net.CharacterRemovePayload;
import dev.tacticalcombat.net.CharacterUpdatePayload;
import dev.tacticalcombat.net.RolePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Server side of the shared characters: players push their sheets, everyone with the mod gets every sheet (others'
 * are read-only for players), Dungeon Masters may change any of them.
 */
public final class CharacterSync {
	private CharacterSync() {}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(CharacterPushPayload.ID, CharacterPushPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CharacterDeletePayload.ID, CharacterDeletePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CharacterUpdatePayload.ID, CharacterUpdatePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CharacterRemovePayload.ID, CharacterRemovePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(RolePayload.ID, RolePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CharacterHidePayload.ID, CharacterHidePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ActiveActorPayload.ID, ActiveActorPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ActiveActorStatePayload.ID, ActiveActorStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CharacterSharePayload.ID, CharacterSharePayload.CODEC);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			CharacterStore.load(server);
			Roles.load(server);
			Actors.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			CharacterStore.save();
			CharacterStore.clear();
			Roles.clear();
			Actors.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendAll(handler.player));

		ServerPlayNetworking.registerGlobalReceiver(CharacterPushPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> push(context.server(), player, payload.id(), payload.data()));
		});
		ServerPlayNetworking.registerGlobalReceiver(ActiveActorPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				CharacterStore.Entry e = CharacterStore.get(payload.id());
				if (!payload.id().isEmpty() && (e == null || !e.owner.equals(player.getUuid()))) return; // only your own characters
				Actors.set(player.getUuid(), payload.id());
				sendActor(player);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(CharacterSharePayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> setShare(context.server(), player, payload.id(), payload.share()));
		});
		ServerPlayNetworking.registerGlobalReceiver(CharacterDeletePayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> delete(context.server(), player, payload.id()));
		});
	}

	private static boolean canChange(ServerPlayerEntity player, CharacterStore.Entry entry) {
		return entry.owner.equals(player.getUuid()) || Roles.isDm(player.getUuid());
	}

	private static void push(MinecraftServer server, ServerPlayerEntity player, String id, byte[] data) {
		if (id.isBlank()) return;
		JsonObject json;
		try {
			json = JsonParser.parseString(new String(Gz.unpack(data).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			TacticalCombatMod.LOGGER.warn("Ignoring a bad character from {}: {}", player.getGameProfile().getName(), e.toString());
			return;
		}
		if (!json.has("format")) return;
		json.addProperty("id", id);
		json.remove("version"); // the store owns the version

		CharacterStore.Entry existing = CharacterStore.get(id);
		if (existing != null) {
			if (!canChange(player, existing)) return;
			if (existing.json.equals(json)) return; // nothing new
			existing.json = json;
			existing.version++;
			CharacterStore.save();
			broadcast(server, existing, player.getUuid().toString());
		} else {
			CharacterStore.Entry created = new CharacterStore.Entry(id, player.getUuid(), player.getGameProfile().getName(), 1, json);
			CharacterStore.put(created);
			CharacterStore.save();
			broadcast(server, created, player.getUuid().toString());
		}
	}

	/** A Dungeon Master decides who besides the owner may see a character. */
	private static void setShare(MinecraftServer server, ServerPlayerEntity player, String id, String share) {
		if (!Roles.isDm(player.getUuid())) return;
		CharacterStore.Entry e = CharacterStore.get(id);
		if (e == null) return;
		StringBuilder clean = new StringBuilder();
		if (share.equals("*")) {
			clean.append('*');
		} else {
			for (String part : share.split(",")) {
				try {
					String u = java.util.UUID.fromString(part.trim()).toString();
					if (clean.length() > 0) clean.append(',');
					clean.append(u);
				} catch (IllegalArgumentException ignored) {
					// skip anything that is not a uuid
				}
			}
		}
		if (e.share.equals(clean.toString())) return;
		e.share = clean.toString();
		CharacterStore.save();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) sendTo(p, e);
	}

	/** Sends the character to a player who may see it, or tells their client to drop it. */
	private static void sendTo(ServerPlayerEntity p, CharacterStore.Entry e) {
		if (!ServerPlayNetworking.canSend(p, CharacterUpdatePayload.ID)) return;
		if (e.visibleTo(p.getUuid(), Roles.isDm(p.getUuid()))) {
			ServerPlayNetworking.send(p, payloadOf(e, ""));
		} else {
			ServerPlayNetworking.send(p, new CharacterHidePayload(e.id));
		}
	}

	private static void delete(MinecraftServer server, ServerPlayerEntity player, String id) {
		CharacterStore.Entry existing = CharacterStore.get(id);
		if (existing == null || !canChange(player, existing)) return;
		CharacterStore.remove(id);
		CharacterStore.save();
		for (java.util.UUID u : Actors.forget(id)) {
			ServerPlayerEntity owner = server.getPlayerManager().getPlayer(u);
			if (owner != null) sendActor(owner);
		}
		CharacterRemovePayload payload = new CharacterRemovePayload(id);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, CharacterRemovePayload.ID)) ServerPlayNetworking.send(p, payload);
		}
	}

	/** Removes a character for the server's own reasons (an Actor with a private sheet was deleted). */
	public static void removeByServer(MinecraftServer server, String id) {
		if (CharacterStore.remove(id) == null) return;
		CharacterStore.save();
		for (java.util.UUID u : Actors.forget(id)) {
			ServerPlayerEntity owner = server.getPlayerManager().getPlayer(u);
			if (owner != null) sendActor(owner);
		}
		CharacterRemovePayload payload = new CharacterRemovePayload(id);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, CharacterRemovePayload.ID)) ServerPlayNetworking.send(p, payload);
		}
	}

	private static CharacterUpdatePayload payloadOf(CharacterStore.Entry e, String by) {
		JsonObject copy = e.json.deepCopy();
		copy.addProperty("version", e.version);
		return new CharacterUpdatePayload(e.id, e.owner.toString(), e.ownerName, by, e.version, e.share, Gz.pack(copy.toString()));
	}

	/** A change made by someone (by = their uuid) or by the server (empty), to everyone with the mod. */
	public static void broadcast(MinecraftServer server, CharacterStore.Entry e, String by) {
		CharacterUpdatePayload payload = payloadOf(e, by);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (e.visibleTo(p.getUuid(), Roles.isDm(p.getUuid())) && ServerPlayNetworking.canSend(p, CharacterUpdatePayload.ID)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** Server-side change (combat: hit points lost, slots spent); owners and DMs see it on their sheets. */
	public static void changedByServer(MinecraftServer server, CharacterStore.Entry e) {
		e.version++;
		CharacterStore.save();
		broadcast(server, e, "");
	}

	public static void sendActor(ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, ActiveActorStatePayload.ID)) {
			ServerPlayNetworking.send(player, new ActiveActorStatePayload(Actors.get(player.getUuid())));
		}
	}

	public static void sendRole(ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, RolePayload.ID)) {
			ServerPlayNetworking.send(player, new RolePayload(Roles.isDm(player.getUuid())));
		}
	}

	/** Everything a joining player may see, then their role (the signal that the sync is complete). */
	private static void sendAll(ServerPlayerEntity player) {
		resync(player);
	}

	/** Sends what this player may see and drops the rest (also after their role changed), then the role. */
	public static void resync(ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, CharacterUpdatePayload.ID)) return;
		for (CharacterStore.Entry e : CharacterStore.all()) sendTo(player, e);
		sendActor(player);
		sendRole(player);
	}
}
