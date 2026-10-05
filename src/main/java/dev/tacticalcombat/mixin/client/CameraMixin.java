package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.ClientCombatState;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tactical camera: independent of where the player looks. It orbits a focus point that follows the
 * active unit (plus the player's WASD pan), at a yaw / pitch / distance the player controls with the mouse.
 * Everything is blended in from the normal camera so the camera visibly rises when combat starts.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean thirdPerson;

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void tacticalcombat$tacticalCamera(BlockView area, Entity focusedEntity, boolean tp, boolean inverseView,
											   float tickDelta, CallbackInfo ci) {
		if (inverseView || focusedEntity == null) return;

		float blend = ClientCombatState.cameraBlend(tickDelta);
		if (blend <= 0.001f) return;

		Camera self = (Camera) (Object) this;
		this.thirdPerson = true; // render the player model, hide the first-person hand

		float baseYaw = self.getYaw();
		float basePitch = self.getPitch();
		float yaw = baseYaw + MathHelper.wrapDegrees(ClientCombatState.lerpedYaw(tickDelta) - baseYaw) * blend;
		float pitch = basePitch + (ClientCombatState.camPitch - basePitch) * blend;
		this.setRotation(yaw, pitch);

		Vec3d eye = focusedEntity.getLerpedPos(tickDelta)
				.add(0, focusedEntity.getEyeHeight(focusedEntity.getPose()), 0);
		Vec3d focus = eye.lerp(ClientCombatState.lerpedFocus(tickDelta), blend);

		Vec3d back = Vec3d.fromPolar(pitch, yaw).multiply(-1);
		double wanted = ClientCombatState.lerpedZoom(tickDelta) * blend;
		Vec3d target = focus.add(back.multiply(wanted));

		// Do not let the camera end up inside solid blocks.
		BlockHitResult hit = area.raycast(new RaycastContext(focus, target,
				RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, focusedEntity));
		if (hit.getType() != HitResult.Type.MISS) {
			double clipped = Math.max(0.5, hit.getPos().distanceTo(focus) - 0.4);
			target = focus.add(back.multiply(Math.min(wanted, clipped)));
		}

		this.setPos(target.x, target.y, target.z);
	}
}
