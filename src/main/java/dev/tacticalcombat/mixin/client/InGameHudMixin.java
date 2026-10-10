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

	/**
	 * For the players in the fight the action bar replaces Minecraft's own hotbar, hearts, armour, food and
	 * experience, so those are not drawn. {@code require = 0}: if one of these methods is named differently in the
	 * game version, that one simply stays visible instead of the mod failing to load.
	 */
	@Inject(method = {"renderHotbar", "renderStatusBars", "renderMountHealth", "renderExperienceBar",
			"renderExperienceLevel", "renderHeldItemTooltip", "renderMountJumpBar"},
			at = @At("HEAD"), cancellable = true, require = 0)
	private void tacticalcombat$hideVanillaHud(CallbackInfo ci) {
		if (ClientCombatState.isFrozen()) {
			ci.cancel();
		}
	}
}
