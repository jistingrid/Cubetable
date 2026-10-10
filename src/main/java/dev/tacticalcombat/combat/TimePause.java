package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.net.PausePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Dungeon Master's time pause. While it is on, players (everyone who is not a Dungeon Master) can not walk, jump
 * or touch blocks (break, place, use). Looking around, the sheet, chat and dice still work. A player in a fight keeps
 * moving through the fight's own grid. The client also stops the keys (so there is no rubber banding); the server
 * is what enforces it. Lasts until the server stops.
 */
public final class TimePause {
	public static boolean paused;

	/** Where each player stood when time stopped (kept up to date while they are standing on the ground). */
	private static final Map<UUID, Vec3d> HOLD = new HashMap<>();

	private TimePause() {}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> held(player) && !world.isClient ? ActionResult.FAIL : ActionResult.PASS);
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> held(player) && !world.isClient ? ActionResult.FAIL : ActionResult.PASS);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sender.sendPacket(new PausePayload(paused)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			paused = false;
			HOLD.clear();
		});
	}

	/** True when this player is stopped by the pause. */
	private static boolean held(PlayerEntity p) {
		return (paused && !Roles.isDm(p.getUuid())) || Downed.isDown(p);
	}

	public static void set(MinecraftServer server, boolean on) {
		if (paused == on) return;
		paused = on;
		HOLD.clear();
		PausePayload payload = new PausePayload(on);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(p, payload);
			p.sendMessage(Text.literal(on ? "Time is paused." : "Time runs again."), true);
		}
	}

	/** Every tick: a player who has been pushed or has moved while time is stopped is put back. */
	public static void tick(MinecraftServer server) {
		if (!paused && !Downed.any()) return;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			boolean stopped = (paused && !Roles.isDm(p.getUuid())) || Downed.isDown(p);
			if (!stopped || p.isSpectator() || (CombatManager.isInCombat(p) && !Downed.isDown(p))) {
				HOLD.remove(p.getUuid());
				continue;
			}
			Vec3d at = HOLD.get(p.getUuid());
			if (at == null || (p.isOnGround() && p.getY() < at.y - 0.01)) {
				HOLD.put(p.getUuid(), p.getPos()); // first sight, or they fell to a lower floor: hold them here
				continue;
			}
			double dx = p.getX() - at.x;
			double dz = p.getZ() - at.z;
			boolean jumped = p.isOnGround() && p.getY() > at.y + 0.1;
			if (dx * dx + dz * dz > 0.0025 || jumped) {
				p.networkHandler.requestTeleport(at.x, at.y, at.z, p.getYaw(), p.getPitch());
				p.setVelocity(Vec3d.ZERO);
			} else if (p.isOnGround()) {
				HOLD.put(p.getUuid(), new Vec3d(at.x, p.getY(), at.z)); // follows slopes and steps, never sideways
			}
		}
	}
}
