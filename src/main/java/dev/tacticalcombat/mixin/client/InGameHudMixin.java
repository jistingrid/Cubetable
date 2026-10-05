package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.ClientCombatState;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	/** The mouse is a free cursor in combat, so the screen-centre crosshair would only be misleading. */
	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void tacticalcombat$hideCrosshair(CallbackInfo ci) {
		if (ClientCombatState.active) {
			ci.cancel();
		}
	}
}
