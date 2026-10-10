package dev.tacticalcombat.mixin.client;

import dev.tacticalcombat.client.DmState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	/**
	 * While a Dungeon Master possesses an Actor, its body sits exactly at the camera: in first person it is not drawn
	 * (everyone else still sees it). {@code require = 0}: if the method is named differently the body just shows.
	 */
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends Entity> void tacticalcombat$hidePossessed(E entity, Frustum frustum, double x, double y, double z,
																 CallbackInfoReturnable<Boolean> cir) {
		if (DmState.possessed >= 0 && entity.getId() == DmState.possessed
				&& MinecraftClient.getInstance().options.getPerspective().isFirstPerson()) {
			cir.setReturnValue(false);
		}
	}
}
