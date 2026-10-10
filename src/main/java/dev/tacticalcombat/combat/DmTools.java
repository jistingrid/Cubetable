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
	public static boolean autoMovement = true;

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
		DmStatePayload payload = new DmStatePayload(autoMovement, who);
		for (ServerPlayerEntity dm : dms) ServerPlayNetworking.send(dm, payload);
	}
}
