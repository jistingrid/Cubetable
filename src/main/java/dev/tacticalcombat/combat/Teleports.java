package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.grid.Grid;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The Dungeon Master's teleport tool: bring players next to the DM, or go to a player. */
public final class Teleports {
	private Teleports() {}

	/** Brings one player (by entity id) next to the DM. */
	public static void bring(ServerPlayerEntity dm, int entityId) {
		Entity e = find(dm, entityId);
		if (!(e instanceof ServerPlayerEntity target) || target == dm) return;
		Set<Long> used = new HashSet<>();
		move(dm, target, used);
		dm.sendMessage(Text.literal("Brought " + target.getName().getString() + " to you."), true);
	}

	/** Brings every player who is not a Dungeon Master next to the DM, each on a square of their own. */
	public static void bringAll(ServerPlayerEntity dm) {
		Set<Long> used = new HashSet<>();
		int n = 0;
		for (ServerPlayerEntity p : dm.getServer().getPlayerManager().getPlayerList()) {
			if (p == dm || Roles.isDm(p.getUuid()) || p.isSpectator()) continue;
			move(dm, p, used);
			n++;
		}
		dm.sendMessage(Text.literal(n == 0 ? "No players to bring." : "Brought " + n + (n == 1 ? " player" : " players") + " to you."), true);
	}

	/** The DM goes to the player. */
	public static void goTo(ServerPlayerEntity dm, int entityId) {
		Entity e = find(dm, entityId);
		if (!(e instanceof ServerPlayerEntity target) || target == dm) return;
		Set<Long> used = new HashSet<>();
		move(target, dm, used);
		dm.sendMessage(Text.literal("Went to " + target.getName().getString() + "."), true);
	}

	private static Entity find(ServerPlayerEntity dm, int entityId) {
		for (ServerPlayerEntity p : dm.getServer().getPlayerManager().getPlayerList()) if (p.getId() == entityId) return p;
		return null;
	}

	/** Moves {@code who} to a free square near {@code anchor} (same world or another one). */
	private static void move(ServerPlayerEntity anchor, ServerPlayerEntity who, Set<Long> used) {
		ServerWorld world = anchor.getServerWorld();
		Vec3d at = spot(world, anchor, who, used);
		TimePause.release(who); // a pause must not drag them back to where they stood
		if (who.getServerWorld() == world) {
			who.networkHandler.requestTeleport(at.x, at.y, at.z, who.getYaw(), who.getPitch());
		} else {
			who.teleport(world, at.x, at.y, at.z, who.getYaw(), who.getPitch());
		}
		who.setVelocity(Vec3d.ZERO);
		who.fallDistance = 0;
	}

	/** A standable square close to the anchor that nobody stands on (rings of growing size), else the anchor's own. */
	private static Vec3d spot(ServerWorld world, ServerPlayerEntity anchor, ServerPlayerEntity who, Set<Long> used) {
		BlockPos base = Grid.cellOf(anchor);
		List<int[]> ring = new ArrayList<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) if (dx != 0 || dz != 0) ring.add(new int[] {dx, dz});
		}
		ring.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
		for (int[] o : ring) {
			for (int dy = 1; dy >= -2; dy--) {
				BlockPos p = base.add(o[0], dy, o[1]);
				if (used.contains(p.asLong()) || !Grid.isStandable(world, p)) continue;
				// the walk there must be possible in a straight line: skip squares behind a wall
				if (!clear(world, base, p)) continue;
				Vec3d centre = Grid.centerOf(world, p);
				Box box = new Box(centre.x - 0.4, centre.y, centre.z - 0.4, centre.x + 0.4, centre.y + 1.8, centre.z + 0.4);
				boolean taken = false;
				for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box, x -> x != who)) taken = true;
				if (taken) continue;
				used.add(p.asLong());
				return centre;
			}
		}
		return anchor.getPos();
	}

	/** No solid block between the two squares at head height (so nobody lands in the next room). */
	private static boolean clear(ServerWorld world, BlockPos a, BlockPos b) {
		int steps = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
		for (int i = 1; i < steps; i++) {
			int x = a.getX() + Math.round((b.getX() - a.getX()) * i / (float) steps);
			int z = a.getZ() + Math.round((b.getZ() - a.getZ()) * i / (float) steps);
			BlockPos mid = new BlockPos(x, a.getY() + 1, z);
			if (!Grid.isPassable(world, mid)) return false;
		}
		return true;
	}
}
