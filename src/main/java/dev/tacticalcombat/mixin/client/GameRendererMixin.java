package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.ClientCombatState;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** The first-person hand floats in mid air when the camera is far away, so hide it. */
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void tacticalcombat$hideHand(CallbackInfo ci) {
		if (ClientCombatState.cameraBlend(1.0f) > 0.01f) {
			ci.cancel();
		}
	}
}
