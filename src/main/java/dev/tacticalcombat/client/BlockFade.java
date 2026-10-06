package dev.tacticalcombat.client;

import dev.tacticalcombat.net.GridPayload;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides which blocks stand between the tactical camera and the unit whose turn it is, and makes them
 * semi-transparent so the camera never has to stop in front of them.
 *
 * <p>The faded area is a cone that is narrow at the unit and wide at the camera, so the whole view onto
 * the unit is cleared, not just a one-block-wide straw. Blocks below the unit's feet (the ground) never fade.
 * The actual translucent rendering is done by {@link FadeModels}; this class only keeps the set of block positions.
 */
public final class BlockFade {
	/** Opacity of a faded block (0 = invisible, 1 = solid). */
	public static final float ALPHA = 0.30f;

	/** The set is recomputed every this many ticks. */
	private static final int INTERVAL = 4;
	/** Blocks within this distance of the unit end of the line are left alone. */
	private static final double END_MARGIN = 1.0;
	private static final double BASE_RADIUS = 0.8;
	private static final double RADIUS_GROWTH = 0.12;
	private static final double MAX_RADIUS = 3.6;

	// Replaced as a whole (never modified), because chunk rebuild threads read them concurrently.
	private static volatile Set<Long> faded = Set.of();
	private static volatile Set<Long> seeThrough = Set.of();
	private static int counter;
	private static boolean force;

	private BlockFade() {}

	/** True if the block at this position is currently drawn semi-transparent. */
	public static boolean isFaded(long pos) {
		Set<Long> f = faded;
		return !f.isEmpty() && f.contains(pos);
	}

	/** True if a highlighted square can only be hidden by faded blocks, so it must be drawn through them. */
	public static boolean isSeeThrough(long pos) {
		Set<Long> s = seeThrough;
		return !s.isEmpty() && s.contains(pos);
	}

	/** Ask for a recomputation on the next tick (new grid, etc.). */
	public static void markDirty() {
		force = true;
	}

	public static void clear() {
		faded = Set.of();
		seeThrough = Set.of();
	}

	public static void tick(MinecraftClient mc) {
		ClientWorld world = mc.world;
		if (world == null || mc.player == null) {
			clear();
			return;
		}
		boolean want = ClientCombatState.active && ClientCombatState.cameraBlend(1.0f) > 0.5f;
		if (!want && faded.isEmpty()) return;
		if (!force && ++counter % INTERVAL != 0) return;
		force = false;

		Set<Long> next = Set.of();
		Vec3d from = null;
		if (want) {
			Entity target = ClientCombatState.activeEntity(mc);
			if (target != null) {
				from = mc.gameRenderer.getCamera().getPos();
				next = computeFaded(world, from, target);
			}
		}

		apply(world, next);
		seeThrough = (from == null || next.isEmpty()) ? Set.of() : computeSeeThrough(world, from, next);
	}

	// ---------------------------------------------------------------- which blocks

	private static double radiusAt(double distanceToUnit) {
		return Math.min(BASE_RADIUS + RADIUS_GROWTH * distanceToUnit, MAX_RADIUS);
	}

	private static Set<Long> computeFaded(ClientWorld world, Vec3d from, Entity target) {
		Vec3d to = target.getPos().add(0, target.getHeight() * 0.6, 0);
		Vec3d seg = to.subtract(from);
		double len = seg.length();
		if (len < 2.0) return Set.of();

		Vec3d dir = seg.multiply(1.0 / len);
		int feetY = MathHelper.floor(target.getY() + 0.05);

		Set<Long> out = new HashSet<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (double t = 0; t <= len - END_MARGIN; t += 0.75) {
			Vec3d p = from.add(dir.multiply(t));
			int r = (int) Math.ceil(radiusAt(len - t));
			int bx = MathHelper.floor(p.x);
			int by = MathHelper.floor(p.y);
			int bz = MathHelper.floor(p.z);

			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					for (int dz = -r; dz <= r; dz++) {
						int x = bx + dx;
						int y = by + dy;
						int z = bz + dz;
						if (y < feetY) continue; // the ground the unit stands on never fades

						m.set(x, y, z);
						long key = m.asLong();
						if (out.contains(key)) continue;

						// distance of the block centre to the camera -> unit line
						double cx = x + 0.5;
						double cy = y + 0.5;
						double cz = z + 0.5;
						double tt = (cx - from.x) * dir.x + (cy - from.y) * dir.y + (cz - from.z) * dir.z;
						if (tt < 0 || tt > len) continue; // behind the camera or behind the unit
						double ex = from.x + dir.x * tt - cx;
						double ey = from.y + dir.y * tt - cy;
						double ez = from.z + dir.z * tt - cz;
						double rad = radiusAt(len - tt);
						if (ex * ex + ey * ey + ez * ez > rad * rad) continue;

						if (occludes(world, m)) out.add(key);
					}
				}
			}
		}
		return out;
	}

	private static boolean occludes(ClientWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		return !s.isAir()
				&& s.getFluidState().isEmpty()
				&& (s.isOpaque() || s.isIn(BlockTags.LEAVES));
	}

	/** Highlighted squares whose line of sight to the camera is blocked only by faded blocks. */
	private static Set<Long> computeSeeThrough(ClientWorld world, Vec3d from, Set<Long> fadedSet) {
		Set<Long> keys = new HashSet<>(ClientGrid.threat);
		for (GridPayload.Cell c : ClientGrid.cells) {
			keys.add(c.pos());
		}

		Set<Long> out = new HashSet<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (long key : keys) {
			BlockPos cell = BlockPos.fromLong(key);
			double dx = cell.getX() + 0.5 - from.x;
			double dy = ClientGrid.surface(world, key) + 0.1 - from.y;
			double dz = cell.getZ() + 0.5 - from.z;
			double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 1.0) continue;

			int steps = (int) Math.ceil(len / 0.4);
			boolean sawFaded = false;
			boolean blocked = false;
			for (int i = 1; i < steps; i++) {
				double t = (double) i / steps;
				m.set(MathHelper.floor(from.x + dx * t), MathHelper.floor(from.y + dy * t), MathHelper.floor(from.z + dz * t));
				long k = m.asLong();
				if (k == key) continue;
				if (fadedSet.contains(k)) {
					sawFaded = true;
					continue;
				}
				if (!world.getBlockState(m).getCollisionShape(world, m).isEmpty()) {
					blocked = true;
					break;
				}
			}
			if (sawFaded && !blocked) out.add(key);
		}
		return out;
	}

	// ---------------------------------------------------------------- applying

	/** Publishes the new set and makes the renderer rebuild the chunk sections whose blocks changed. */
	private static void apply(ClientWorld world, Set<Long> next) {
		Set<Long> old = faded;
		if (old.equals(next)) return;

		Map<Long, BlockPos> sections = new HashMap<>();
		for (long l : old) {
			if (!next.contains(l)) markSection(sections, l);
		}
		for (long l : next) {
			if (!old.contains(l)) markSection(sections, l);
		}

		faded = next; // publish first: the rebuild reads it
		for (BlockPos p : sections.values()) {
			BlockState s = world.getBlockState(p);
			world.updateListeners(p, s, s, 0); // schedules a re-render of the section around p
		}
	}

	private static void markSection(Map<Long, BlockPos> sections, long packed) {
		BlockPos p = BlockPos.fromLong(packed);
		long section = (((long) (p.getX() >> 4)) & 0x3FFFFF) << 42
				| (((long) (p.getY() >> 4)) & 0xFFFFF)
				| (((long) (p.getZ() >> 4)) & 0x3FFFFF) << 20;
		sections.putIfAbsent(section, p);
	}
}
