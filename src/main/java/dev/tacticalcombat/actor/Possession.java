package dev.tacticalcombat.actor;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.combat.CombatManager;
import dev.tacticalcombat.net.PossessPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A Dungeon Master possessing an Actor outside a fight. The Minecraft rule that a player can not steer another entity
 * is met by letting the DM's own player do the walking: the DM is moved next to the Actor, made invisible, and the
 * Actor's body is held on the DM's position, look and head every tick. The DM moves in first person, breaks and
 * places blocks and uses things as usual (with their own inventory), while everyone else sees the Actor doing it.
 * Releasing puts the DM back where they started; the Actor stays where they left it.
 */
public final class Possession {
	private record State(String actorId, ServerWorld world, Vec3d origin, float yaw, float pitch) {}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();

	private Possession() {}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			State s = ACTIVE.remove(handler.player.getUuid());
			if (s != null) handler.player.removeStatusEffect(StatusEffects.INVISIBILITY);
		});
	}

	public static boolean isPossessing(ServerPlayerEntity dm) {
		return ACTIVE.containsKey(dm.getUuid());
	}

	/** Null when it worked, else why not. */
	public static String start(ServerPlayerEntity dm, String id) {
		if (!Roles.isDm(dm.getUuid())) return "Only a Dungeon Master can possess an Actor.";
		MinecraftServer server = dm.getServer();
		ActorRecord r = ActorRegistry.find(id);
		if (r == null || server == null) return "No such Actor.";
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		if (body == null) return r.name + " is not placed in the world.";
		if (CombatManager.isInCombat(body)) return r.name + " is in a fight: it moves on the grid.";
		if (CombatManager.isInCombat(dm)) return "You are in a fight.";
		for (Map.Entry<UUID, State> e : ACTIVE.entrySet()) {
			if (e.getValue().actorId.equals(r.id) && !e.getKey().equals(dm.getUuid())) return r.name + " is already possessed.";
		}
		if (isPossessing(dm)) release(dm, true);
		if (!(dm.getWorld() instanceof ServerWorld here) || !(body.getWorld() instanceof ServerWorld there)) return "Not in a world.";

		ACTIVE.put(dm.getUuid(), new State(r.id, here, dm.getPos(), dm.getYaw(), dm.getPitch()));
		dm.teleport(there, body.getX(), body.getY(), body.getZ(), body.getYaw(), body.getPitch());
		hide(dm);
		ServerPlayNetworking.send(dm, new PossessPayload(body.getId()));
		dm.sendMessage(Text.literal("You are " + r.name + ". Press P to release."), true);
		return null;
	}

	/** Puts the DM back (when {@code back}) and lets the Actor go. */
	public static void release(ServerPlayerEntity dm, boolean back) {
		State s = ACTIVE.remove(dm.getUuid());
		if (s == null) return;
		dm.removeStatusEffect(StatusEffects.INVISIBILITY);
		if (back) dm.teleport(s.world, s.origin.x, s.origin.y, s.origin.z, s.yaw, s.pitch);
		ServerPlayNetworking.send(dm, new PossessPayload(-1));
	}

	private static void hide(ServerPlayerEntity dm) {
		dm.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, false, false, false));
	}

	/** Every tick: the body follows its possessor; anything that makes that impossible ends it. */
	public static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty()) return;
		Iterator<Map.Entry<UUID, State>> it = ACTIVE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, State> e = it.next();
			ServerPlayerEntity dm = server.getPlayerManager().getPlayer(e.getKey());
			if (dm == null) {
				it.remove();
				continue;
			}
			ActorRecord r = ActorRegistry.get(e.getValue().actorId);
			LivingEntity body = r == null ? null : ActorRegistry.bodyOf(server, r);
			String stop = null;
			if (body == null) stop = "The Actor is gone.";
			else if (CombatManager.isInCombat(body) || CombatManager.isInCombat(dm)) stop = "A fight began: possession ends.";
			else if (body.getWorld() != dm.getWorld()) stop = "You left the Actor's dimension: possession ends.";
			if (stop != null) {
				it.remove();
				dm.removeStatusEffect(StatusEffects.INVISIBILITY);
				ServerPlayNetworking.send(dm, new PossessPayload(-1));
				dm.sendMessage(Text.literal(stop), false);
				continue;
			}
			if (body instanceof MobEntity m) m.getNavigation().stop();
			body.refreshPositionAndAngles(dm.getX(), dm.getY(), dm.getZ(), dm.getYaw(), dm.getPitch());
			body.setHeadYaw(dm.getHeadYaw());
			body.setBodyYaw(dm.getBodyYaw());
			body.fallDistance = 0;
			if (server.getTicks() % 20 == 0) hide(dm);
		}
	}
}
