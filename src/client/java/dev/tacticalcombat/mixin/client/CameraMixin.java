package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.ClientCombatState;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pulls the camera back and up while in combat: a third-person "behind the head" camera whose distance
 * grows with the combat blend. It looks along the player's view direction, so the crosshair still lines up
 * with what the player aims at.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	/** How far behind the player the fully risen camera sits (blocks). */
	private static final double TACTICAL_DISTANCE = 16.0;

	@Shadow
	private boolean thirdPerson;

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Inject(method = "update", at = @At("TAIL"))
	private void tacticalcombat$riseCamera(BlockView area, Entity focusedEntity, boolean tp, boolean inverseView,
										   float tickDelta, CallbackInfo ci) {
		if (inverseView || focusedEntity == null) return;

		float blend = ClientCombatState.cameraBlend(tickDelta);
		if (blend <= 0.001f) return;

		Camera self = (Camera) (Object) this;
		this.thirdPerson = true; // render the player model, hide the first-person hand

		Vec3d eye = focusedEntity.getLerpedPos(tickDelta)
				.add(0, focusedEntity.getEyeHeight(focusedEntity.getPose()), 0);
		Vec3d back = Vec3d.fromPolar(self.getPitch(), self.getYaw()).multiply(-1);

		double wanted = TACTICAL_DISTANCE * blend;
		Vec3d target = eye.add(back.multiply(wanted));

		// Do not let the camera end up inside solid blocks.
		BlockHitResult hit = area.raycast(new RaycastContext(eye, target,
				RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, focusedEntity));
		if (hit.getType() != HitResult.Type.MISS) {
			double clipped = Math.max(0.5, hit.getPos().distanceTo(eye) - 0.4);
			target = eye.add(back.multiply(Math.min(wanted, clipped)));
		}

		this.setPos(target.x, target.y, target.z);
	}
}
