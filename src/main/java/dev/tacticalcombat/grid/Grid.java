package dev.tacticalcombat.grid;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The square grid the combat is played on. One square = one block column position, identified by the
 * block position where the feet stand. Movement is 4-directional, plus diagonals when the game's pack allows them; every step costs 1 square.
 * Shared by server (pathfinding, validation) and client (highlight height).
 */
public final class Grid {
	/** Highest drop (in blocks) a single step may take. */
	public static final int MAX_DROP = 3;
	/** Melee reach used for click-to-attack, measured eye -> nearest point of the target's hitbox. */
	public static final double ATTACK_REACH = 3.2;

	private Grid() {}

	public static final class Node {
		public final long pos;
		public final int cost;
		/** Index of the previous node in {@link Result#nodes}, -1 for the start square. */
		public final int parent;
		/** False for the start square and squares occupied by another combatant. */
		public boolean endable = true;

		Node(long pos, int cost, int parent) {
			this.pos = pos;
			this.cost = cost;
			this.parent = parent;
		}
	}

	public static final class Result {
		public final List<Node> nodes = new ArrayList<>();
		public final Map<Long, Integer> index = new HashMap<>();

		void add(long pos, int cost, int parent) {
			index.put(pos, nodes.size());
			nodes.add(new Node(pos, cost, parent));
		}
	}

	// ---------------------------------------------------------------- search

	/**
	 * Breadth-first search over walkable squares.
	 * @param blocked squares that can not be entered at all (enemies)
	 * @param diagonals whether a step to a corner square is allowed (it then costs 1 like a straight step and may not cut a
	 *                  wall's corner: both squares beside it must be free of blocks)
	 */
	public static Result reachable(World world, BlockPos start, int budget, Set<Long> blocked, boolean diagonals) {
		Result r = new Result();
		r.add(start.asLong(), 0, -1);
		for (int i = 0; i < r.nodes.size(); i++) {
			Node n = r.nodes.get(i);
			if (n.cost >= budget) continue;
			BlockPos from = BlockPos.fromLong(n.pos);
			for (Direction dir : Direction.Type.HORIZONTAL) {
				BlockPos next = step(world, from, dir);
				if (next == null) continue;
				long key = next.asLong();
				if (r.index.containsKey(key) || blocked.contains(key)) continue;
				r.add(key, n.cost + 1, i);
			}
			if (!diagonals) continue;
			for (Direction a : new Direction[] {Direction.NORTH, Direction.SOUTH}) {
				for (Direction b : new Direction[] {Direction.EAST, Direction.WEST}) {
					BlockPos next = stepDiagonal(world, from, a, b);
					if (next == null) continue;
					long key = next.asLong();
					if (r.index.containsKey(key) || blocked.contains(key)) continue;
					r.add(key, n.cost + 1, i);
				}
			}
		}
		return r;
	}

	/** The square reached by walking one step from {@code from} towards {@code dir}, or null if that is impossible. */
	public static BlockPos step(World world, BlockPos from, Direction dir) {
		return stepInto(world, from, from.offset(dir));
	}

	/**
	 * The square reached by a diagonal step, or null. Like a straight step, and in addition neither of the two squares
	 * beside the path (at feet and head height) may hold a block, so nobody cuts a corner of a wall.
	 */
	public static BlockPos stepDiagonal(World world, BlockPos from, Direction a, Direction b) {
		for (Direction side : new Direction[] {a, b}) {
			BlockPos beside = from.offset(side);
			if (!isPassable(world, beside) || !isPassable(world, beside.up())) return null;
		}
		return stepInto(world, from, from.offset(a).offset(b));
	}

	private static BlockPos stepInto(World world, BlockPos from, BlockPos column) {
		for (int dy = 1; dy >= -MAX_DROP; dy--) {
			BlockPos cand = column.add(0, dy, 0);
			if (!isStandable(world, cand)) continue;
			// hopping up needs room above the square we leave
			if (dy == 1 && !isPassable(world, from.up(2))) return null;
			// walking off an edge needs the whole shaft down to the landing to be free
			for (int k = 1; k <= 1 - dy; k++) {
				if (!isPassable(world, cand.up(k))) return null;
			}
			return cand;
		}
		return null;
	}

	// ---------------------------------------------------------------- block rules

	public static boolean isStandable(World world, BlockPos p) {
		if (!isLoaded(world, p) || p.getY() <= world.getBottomY() || p.getY() >= world.getTopY() - 2) return false;

		BlockState own = world.getBlockState(p);
		BlockState head = world.getBlockState(p.up());
		BlockState below = world.getBlockState(p.down());

		if (isHazard(own) || isHazard(head) || isHazard(below)) return false;
		if (!own.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
		if (!head.getCollisionShape(world, p.up()).isEmpty()) return false;

		VoxelShape ownShape = own.getCollisionShape(world, p);
		double ownTop = ownShape.isEmpty() ? 0.0 : ownShape.getMax(Direction.Axis.Y);
		if (ownTop > 0.5625) return false;
		// a low floor inside this very square (bottom slab, trapdoor): needs one more block of headroom
		if (ownTop > 0.125) return isPassable(world, p.up(2));

		// otherwise the floor is the full block below (carpet / snow layer inside the square is fine)
		VoxelShape belowShape = below.getCollisionShape(world, p.down());
		// a full block, or one a little lower (dirt path, farmland: 15/16)
		if (belowShape.isEmpty()) return false;
		double top = belowShape.getMax(Direction.Axis.Y);
		return top > 0.9 && top < 1.01;
	}

	public static boolean isPassable(World world, BlockPos p) {
		if (!isLoaded(world, p)) return false;
		BlockState s = world.getBlockState(p);
		return s.getCollisionShape(world, p).isEmpty()
				&& !isHazard(s)
				&& !s.getFluidState().isIn(FluidTags.LAVA);
	}

	private static boolean isLoaded(World world, BlockPos p) {
		return world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
	}

	private static boolean isHazard(BlockState s) {
		return s.isOf(Blocks.MAGMA_BLOCK)
				|| s.isOf(Blocks.CACTUS)
				|| s.isOf(Blocks.SWEET_BERRY_BUSH)
				|| s.isOf(Blocks.COBWEB)
				|| s.isOf(Blocks.POWDER_SNOW)
				|| s.isOf(Blocks.WITHER_ROSE)
				|| s.isOf(Blocks.LAVA)
				|| s.isIn(BlockTags.FIRE)
				|| s.isIn(BlockTags.CAMPFIRES);
	}

	// ---------------------------------------------------------------- geometry helpers

	/** World Y a creature stands at when it is on square {@code p}. */
	public static double surfaceY(BlockView world, BlockPos p) {
		VoxelShape own = world.getBlockState(p).getCollisionShape(world, p);
		if (!own.isEmpty()) return p.getY() + own.getMax(Direction.Axis.Y);
		BlockPos down = p.down();
		VoxelShape below = world.getBlockState(down).getCollisionShape(world, down);
		if (!below.isEmpty()) return down.getY() + below.getMax(Direction.Axis.Y);
		return p.getY();
	}

	/** The square an entity is currently standing on. */
	public static BlockPos cellOf(Entity e) {
		return BlockPos.ofFloored(e.getX(), e.getY() + 0.05, e.getZ());
	}

	public static Vec3d centerOf(BlockView world, BlockPos p) {
		return new Vec3d(p.getX() + 0.5, surfaceY(world, p), p.getZ() + 0.5);
	}

	/** Distance from a point to the closest point of a box. */
	public static double distanceToBox(Vec3d point, Box box) {
		double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
