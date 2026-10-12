package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.net.DmStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** The Dungeon Master's switches and what they are told while no fight is on. Settings last until the server stops. */
public final class DmTools {
	/** True: creatures take their own turns. False: the Dungeon Master walks them square by square and ends their turns. */
	/** Off by default: while a Dungeon Master is connected they walk the creatures themselves. */
	public static boolean autoMovement = false;

	/** True when at least one Dungeon Master is connected. */
	public static boolean dmOnline(MinecraftServer server) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (Roles.isDm(p.getUuid())) return true;
		return false;
	}

	private DmTools() {}

	/** What the Dungeon Master ticked or unticked in the encounter window (by entity); cleared when the fight ends. */
	private static final java.util.Map<java.util.UUID, Boolean> CHECKS = new java.util.HashMap<>();

	public static void clearChecks() {
		CHECKS.clear();
	}

	/** Unticked by the Dungeon Master: nothing pulls this creature into the fight by itself. */
	public static boolean isExcluded(net.minecraft.entity.Entity e) {
		return Boolean.FALSE.equals(CHECKS.get(e.getUuid()));
	}

	/**
	 * Is this creature in the fight, or going to be? In a fight: whether it is in it. Before: what the Dungeon Master
	 * set, else every player and every hostile Actor within reach of a player.
	 */
	public static boolean isChecked(net.minecraft.entity.LivingEntity e) {
		if (CombatManager.primary() != null) return CombatManager.isInCombat(e);
		Boolean set = CHECKS.get(e.getUuid());
		if (set != null) return set;
		if (e instanceof ServerPlayerEntity) return true;
		if (!dev.tacticalcombat.actor.ActorRegistry.isHostileBody(e)) return false;
		if (e.getServer() == null) return false;
		for (ServerPlayerEntity p : e.getServer().getPlayerManager().getPlayerList()) {
			if (!p.isSpectator() && CombatManager.isNear(e, p, CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL)) return true;
		}
		return false;
	}

	private static void setChecked(MinecraftServer server, net.minecraft.entity.LivingEntity e, boolean on) {
		CHECKS.put(e.getUuid(), on);
		Combat running = CombatManager.primary();
		if (running == null) return;
		if (on) running.add(e);
		else running.remove(e);
	}

	/** Everyone the Dungeon Master could put in a fight: players (not spectators) and Actors placed in the world. */
	private static List<net.minecraft.entity.LivingEntity> candidates(MinecraftServer server) {
		List<net.minecraft.entity.LivingEntity> list = new ArrayList<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (!p.isSpectator()) list.add(p);
		for (dev.tacticalcombat.actor.ActorRecord r : dev.tacticalcombat.actor.ActorRegistry.all()) {
			net.minecraft.entity.LivingEntity body = dev.tacticalcombat.actor.ActorRegistry.bodyOf(server, r);
			if (body != null && body.isAlive()) list.add(body);
		}
		return list;
	}

	private static net.minecraft.entity.LivingEntity candidate(MinecraftServer server, int entityId) {
		for (net.minecraft.entity.LivingEntity e : candidates(server)) if (e.getId() == entityId) return e;
		return null;
	}

	public static void action(ServerPlayerEntity player, int op, int value) {
		if (!Roles.isDm(player.getUuid()) || player.getServer() == null) return;
		switch (op) {
			case 0 -> {
				String problem = CombatManager.startSelected(player);
				if (problem != null) player.sendMessage(Text.translatable(problem), false);
			}
			case 3 -> { // tick or untick one participant
				net.minecraft.entity.LivingEntity e = candidate(player.getServer(), value);
				if (e != null) setChecked(player.getServer(), e, !isChecked(e));
			}
			case 4 -> { // everybody in (1) or out (0)
				for (net.minecraft.entity.LivingEntity e : candidates(player.getServer())) {
					if (isChecked(e) != (value != 0)) setChecked(player.getServer(), e, value != 0);
				}
			}
			case 2 -> TimePause.set(player.getServer(), value != 0);
			case 1 -> {
				autoMovement = value != 0;
				Combat running = CombatManager.primary();
				if (running != null) running.markGridDirty(); // the squares of a creature's turn appear or go
			}
			default -> {
				return;
			}
		}
		broadcast(player.getServer());
	}

	/** Hit points of a sheet that has no body in the world yet. */
	private static SheetHealth.Hp readHp(dev.tacticalcombat.character.CharacterStore.Entry sheet) {
		try {
			dev.tacticalcombat.sheet.SheetFormat f = dev.tacticalcombat.sheet.SheetLibrary.FORMATS.get(
					sheet.json.has("format") ? sheet.json.get("format").getAsString() : "");
			return f == null ? null : SheetHealth.read(f, dev.tacticalcombat.sheet.CharacterData.parse(sheet.json.deepCopy(), ""));
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** Every second: tell the DMs how things stand. */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 == 0) broadcast(server);
	}

	public static void broadcast(MinecraftServer server) {
		List<ServerPlayerEntity> dms = new ArrayList<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (Roles.isDm(p.getUuid())) dms.add(p);
		if (dms.isEmpty()) return;

		List<DmStatePayload.Who> who = new ArrayList<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.isSpectator()) continue;
			SheetHealth.Hp hp = SheetHealth.of(p);
			who.add(new DmStatePayload.Who(p.getId(), p.getName().getString(),
					hp != null ? (float) hp.now() : p.getHealth(), hp != null ? (float) hp.max() : p.getMaxHealth(),
					CombatManager.isInCombat(p)));
		}
		List<DmStatePayload.ActorInfo> actors = new ArrayList<>();
		for (dev.tacticalcombat.actor.ActorRecord r : dev.tacticalcombat.actor.ActorRegistry.all()) {
			net.minecraft.entity.LivingEntity body = dev.tacticalcombat.actor.ActorRegistry.bodyOf(server, r);
			SheetHealth.Hp hp = body == null ? null : SheetHealth.of(body);
			if (hp == null) {
				dev.tacticalcombat.character.CharacterStore.Entry sheet = dev.tacticalcombat.actor.ActorRegistry.sheetOf(r);
				hp = sheet == null ? null : readHp(sheet);
			}
			actors.add(new DmStatePayload.ActorInfo(r.id, r.name, r.sheetId, r.ownsSheet, r.kind, r.value, r.disposition,
					r.dmControl, body == null ? -1 : body.getId(), hp == null ? 0f : (float) hp.now(), hp == null ? 0f : (float) hp.max(),
					body != null && CombatManager.isInCombat(body), r.folder, dev.tacticalcombat.actor.ActorRegistry.joinTags(r.tags)));
		}
		// formats that characters use and this server has no pack for: without them there are no hit points or combat rules
		java.util.Set<String> missing = new java.util.LinkedHashSet<>();
		for (dev.tacticalcombat.character.CharacterStore.Entry e : dev.tacticalcombat.character.CharacterStore.all()) {
			String format = e.json.has("format") ? e.json.get("format").getAsString() : "";
			if (!format.isEmpty() && !dev.tacticalcombat.sheet.SheetLibrary.FORMATS.containsKey(format) && format.length() <= 60) missing.add(format);
		}
		List<DmStatePayload.Member> roster = new ArrayList<>();
		for (net.minecraft.entity.LivingEntity e : candidates(server)) {
			SheetHealth.Hp hp = SheetHealth.of(e);
			boolean player = e instanceof ServerPlayerEntity;
			roster.add(new DmStatePayload.Member(e.getId(), e.getName().getString(),
					hp != null ? (float) hp.now() : e.getHealth(), hp != null ? (float) hp.max() : e.getMaxHealth(),
					!player && dev.tacticalcombat.actor.ActorRegistry.isHostileBody(e), player, isChecked(e)));
		}
		DmStatePayload payload = new DmStatePayload(autoMovement, who, actors, dev.tacticalcombat.actor.ActorRegistry.folders(),
				new ArrayList<>(missing), roster);
		for (ServerPlayerEntity dm : dms) ServerPlayNetworking.send(dm, payload);
	}
}
