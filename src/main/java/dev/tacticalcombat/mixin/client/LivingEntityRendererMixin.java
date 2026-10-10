package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.DownedCard;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/**
	 * A downed, stable or dead character or Actor lies on its side, like a creature that has just died. {@code require = 0}:
	 * if the method is named differently in the game version the model simply stays upright.
	 */
	@Inject(method = "setupTransforms", at = @At("TAIL"), require = 0)
	private void tacticalcombat$lieDown(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw,
										float tickDelta, float scale, CallbackInfo ci) {
		if (DownedCard.isDown(entity.getId())) {
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90.0f));
		}
	}
}
