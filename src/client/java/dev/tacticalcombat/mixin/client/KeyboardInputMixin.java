package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.ClientCombatState;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops the local player from walking while it is not their turn (or when their movement is used up). */
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
		} else if (ClientCombatState.isOutOfMovement()) {
			this.pressingForward = false;
			this.pressingBack = false;
			this.pressingLeft = false;
			this.pressingRight = false;
			this.movementForward = 0f;
			this.movementSideways = 0f;
		}
	}
}
