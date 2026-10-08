package dev.tacticalcombat.character;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.net.CharacterDeletePayload;
import dev.tacticalcombat.net.CharacterPushPayload;
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

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			CharacterStore.load(server);
			Roles.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			CharacterStore.save();
			CharacterStore.clear();
			Roles.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendAll(handler.player));

		ServerPlayNetworking.registerGlobalReceiver(CharacterPushPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> push(context.server(), player, payload.id(), payload.data()));
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

	private static void delete(MinecraftServer server, ServerPlayerEntity player, String id) {
		CharacterStore.Entry existing = CharacterStore.get(id);
		if (existing == null || !canChange(player, existing)) return;
		CharacterStore.remove(id);
		CharacterStore.save();
		CharacterRemovePayload payload = new CharacterRemovePayload(id);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, CharacterRemovePayload.ID)) ServerPlayNetworking.send(p, payload);
		}
	}

	private static CharacterUpdatePayload payloadOf(CharacterStore.Entry e, String by) {
		JsonObject copy = e.json.deepCopy();
		copy.addProperty("version", e.version);
		return new CharacterUpdatePayload(e.id, e.owner.toString(), e.ownerName, by, e.version, Gz.pack(copy.toString()));
	}

	/** A change made by someone (by = their uuid) or by the server (empty), to everyone with the mod. */
	public static void broadcast(MinecraftServer server, CharacterStore.Entry e, String by) {
		CharacterUpdatePayload payload = payloadOf(e, by);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, CharacterUpdatePayload.ID)) ServerPlayNetworking.send(p, payload);
		}
	}

	/** Server-side change (combat: hit points lost, slots spent); owners and DMs see it on their sheets. */
	public static void changedByServer(MinecraftServer server, CharacterStore.Entry e) {
		e.version++;
		CharacterStore.save();
		broadcast(server, e, "");
	}

	public static void sendRole(ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, RolePayload.ID)) {
			ServerPlayNetworking.send(player, new RolePayload(Roles.isDm(player.getUuid())));
		}
	}

	/** Everything a joining player needs: all characters, then their role (the signal that the sync is complete). */
	private static void sendAll(ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, CharacterUpdatePayload.ID)) return;
		for (CharacterStore.Entry e : CharacterStore.all()) {
			ServerPlayNetworking.send(player, payloadOf(e, ""));
		}
		sendRole(player);
	}
}
