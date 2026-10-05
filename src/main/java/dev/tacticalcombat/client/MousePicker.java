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
		if (!ClientCombatState.isMyTurn() || mc.world == null || mc.player == null) return;

		Camera camera = mc.gameRenderer.getCamera();
		Vec3d origin = camera.getPos();
		Vec3d dir = rayDirection(mc, camera, mouseX, mouseY);
		Vec3d end = origin.add(dir.multiply(RANGE));

		BlockHitResult hit = mc.world.raycast(new RaycastContext(origin, end,
				RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
		boolean blockHit = hit.getType() == HitResult.Type.BLOCK;
		double blockDist = blockHit ? hit.getPos().distanceTo(origin) : Double.MAX_VALUE;

		// hostile combatants first, if they are in front of the terrain
		double best = blockDist;
		int bestId = -1;
		for (CombatStatePayload.Entry e : ClientCombatState.entries) {
			if (!e.hostile()) continue;
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
		if (!blockHit) return;

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
