package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.ClientCombatState;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Safety net: during combat the local player never walks on their own, all movement goes through the grid.
 * (Normally the tactical screen already swallows the keys; this covers menus that were opened on top of it.)
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
	@Inject(method = "tick", at = @At("TAIL"))
	private void tacticalcombat$lockMovement(CallbackInfo ci) {
		if (ClientCombatState.isFrozen()) {
			this.pressingForward = false;
			this.pressingBack = false;
			this.pressingLeft = false;
			this.pressingRight = false;
			this.movementForward = 0f;
			this.movementSideways = 0f;
			this.jumping = false;
			this.sneaking = false;
		}
	}
}
