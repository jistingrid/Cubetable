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

	public static void action(ServerPlayerEntity player, int op, int value) {
		if (!Roles.isDm(player.getUuid()) || player.getServer() == null) return;
		switch (op) {
			case 0 -> {
				if (CombatManager.startFor(player) == null) {
					player.sendMessage(Text.translatable(CombatManager.primary() != null
							? "tacticalcombat.msg.already_fighting" : "tacticalcombat.msg.no_enemies"), false);
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
					body != null && CombatManager.isInCombat(body)));
		}
		DmStatePayload payload = new DmStatePayload(autoMovement, who, actors);
		for (ServerPlayerEntity dm : dms) ServerPlayNetworking.send(dm, payload);
	}
}
