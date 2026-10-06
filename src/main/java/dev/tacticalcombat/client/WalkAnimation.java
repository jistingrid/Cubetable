package dev.tacticalcombat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * The server moves the local player by teleporting them along the path, which vanilla never sees as walking
 * (no limb swing, no turning). This derives both from the per-tick position change while a fight is on.
 */
public final class WalkAnimation {
	private static double lastX, lastZ;
	private static boolean hasLast;

	private WalkAnimation() {}

	public static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || !ClientCombatState.active) {
			hasLast = false;
			return;
		}
		double x = player.getX();
		double z = player.getZ();
		if (hasLast) {
			double dx = x - lastX;
			double dz = z - lastZ;
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist > 0.02 && dist < 3.0) {
				float want = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
				float yaw = player.bodyYaw + MathHelper.clamp(MathHelper.wrapDegrees(want - player.bodyYaw), -45f, 45f);
				player.setYaw(yaw);
				player.setBodyYaw(yaw);
				player.setHeadYaw(yaw);
				player.prevBodyYaw = yaw;
				player.prevHeadYaw = yaw;
				player.limbAnimator.updateLimbs(Math.min((float) dist * 4.0f, 1.0f), 0.4f);
			} else {
				player.limbAnimator.updateLimbs(0.0f, 0.4f);
			}
		}
		lastX = x;
		lastZ = z;
		hasLast = true;
	}
}
