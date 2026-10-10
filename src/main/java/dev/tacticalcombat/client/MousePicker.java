package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/** Turns the mouse position into "which grid square / which enemy is the cursor on". */
public final class MousePicker {
	private static final double RANGE = 96.0;

	private MousePicker() {}

	/** @param mouseX @param mouseY GUI-scaled mouse coordinates */
	public static void update(MinecraftClient mc, double mouseX, double mouseY) {
		ClientGrid.hover = -1;
		ClientGrid.hoverEntity = -1;
		if (!ClientCombatState.active || mc.world == null || mc.player == null) return;

		Camera camera = mc.gameRenderer.getCamera();
		Vec3d origin = camera.getPos();
		Vec3d dir = rayDirection(mc, camera, mouseX, mouseY);
		Vec3d end = origin.add(dir.multiply(RANGE));

		BlockHitResult hit = raycastSkippingFaded(mc, origin, end);
		boolean blockHit = hit.getType() == HitResult.Type.BLOCK;
		double blockDist = blockHit ? hit.getPos().distanceTo(origin) : Double.MAX_VALUE;

		// any combatant (creatures and players alike) can be clicked to target it, if it is in front of the terrain
		double best = blockDist;
		int bestId = -1;
		for (CombatStatePayload.Entry e : ClientCombatState.entries) {
			Entity entity = mc.world.getEntityById(e.entityId());
			if (entity == null) continue;
			Optional<Vec3d> r = entity.getBoundingBox().expand(0.2).raycast(origin, end);
			if (r.isEmpty()) continue;
			double d = r.get().distanceTo(origin);
			if (d < best) {
				best = d;
				bestId = e.entityId();
			}
		}
		if (bestId >= 0) {
			ClientGrid.hoverEntity = bestId;
			return;
		}
		if (!blockHit || !ClientCombatState.canMoveActive()) return; // squares only matter while you can move the unit on turn

		BlockPos bp = hit.getBlockPos();
		Direction side = hit.getSide();
		BlockPos[] candidates = side == Direction.UP
				? new BlockPos[]{bp.up(), bp}
				: new BlockPos[]{bp.offset(side), bp.offset(side).down(), bp.up(), bp};
		for (BlockPos c : candidates) {
			Integer idx = ClientGrid.indexOf(c.asLong());
			if (idx != null && ClientGrid.cells.get(idx).endable()) {
				ClientGrid.hover = idx;
				return;
			}
		}
	}

	/** The camera ray through a GUI pixel as {origin, far end}, for screens that are not the tactical one. */
	public static Vec3d[] ray(MinecraftClient mc, double mouseX, double mouseY) {
		Camera camera = mc.gameRenderer.getCamera();
		Vec3d origin = camera.getPos();
		return new Vec3d[] {origin, origin.add(rayDirection(mc, camera, mouseX, mouseY).multiply(RANGE))};
	}

	/** Where a ray first meets the ground (see-through blocks skipped), or null when it meets nothing. */
	public static Vec3d groundAt(MinecraftClient mc, Vec3d origin, Vec3d end) {
		BlockHitResult hit = raycastSkippingFaded(mc, origin, end);
		return hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : null;
	}

	/**
	 * Block raycast that looks straight through faded blocks, so a click lands on the square behind a
	 * see-through wall instead of on the wall itself.
	 */
	private static BlockHitResult raycastSkippingFaded(MinecraftClient mc, Vec3d origin, Vec3d end) {
		Vec3d dir = end.subtract(origin).normalize();
		Vec3d start = origin;
		for (int i = 0; i < 64; i++) {
			BlockHitResult hit = mc.world.raycast(new RaycastContext(start, end,
					RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
			if (hit.getType() != HitResult.Type.BLOCK || !BlockFade.isFaded(hit.getBlockPos().asLong())) {
				return hit;
			}

			// walk out of the faded block along the ray, then continue from there
			BlockPos fadedPos = hit.getBlockPos();
			Vec3d s = hit.getPos();
			for (int k = 0; k < 64 && BlockPos.ofFloored(s).equals(fadedPos); k++) {
				s = s.add(dir.multiply(0.05));
			}
			start = s;
			if (start.squaredDistanceTo(end) < 0.01) break;
		}
		return BlockHitResult.createMissed(end, Direction.UP, BlockPos.ofFloored(end));
	}

	/**
	 * Where a point of the world lands on the GUI (scaled pixels), or null when it is behind the camera.
	 * The inverse of {@link #rayDirection}.
	 */
	public static double[] project(MinecraftClient mc, Vec3d point) {
		Camera camera = mc.gameRenderer.getCamera();
		Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
		Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0)).normalize();
		Vec3d up = right.crossProduct(forward);
		Vec3d d = point.subtract(camera.getPos());
		double depth = d.dotProduct(forward);
		if (depth < 0.1) return null;
		double tanHalfFov = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
		double aspect = (double) mc.getWindow().getFramebufferWidth() / (double) mc.getWindow().getFramebufferHeight();
		double nx = d.dotProduct(right) / depth / (tanHalfFov * aspect);
		double ny = -d.dotProduct(up) / depth / tanHalfFov;
		return new double[] {(nx + 1.0) / 2.0 * mc.getWindow().getScaledWidth(), (ny + 1.0) / 2.0 * mc.getWindow().getScaledHeight()};
	}

	/** Direction of the ray through the given GUI pixel, for the current camera. */
	private static Vec3d rayDirection(MinecraftClient mc, Camera camera, double mouseX, double mouseY) {
		Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
		Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0)).normalize();
		Vec3d up = right.crossProduct(forward);

		double tanHalfFov = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
		double aspect = (double) mc.getWindow().getFramebufferWidth() / (double) mc.getWindow().getFramebufferHeight();
		double nx = mouseX / mc.getWindow().getScaledWidth() * 2.0 - 1.0;
		double ny = mouseY / mc.getWindow().getScaledHeight() * 2.0 - 1.0;

		return forward
				.add(right.multiply(nx * tanHalfFov * aspect))
				.add(up.multiply(-ny * tanHalfFov))
				.normalize();
	}
}
