package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Client side mirror of the combat the local player is in, plus the tactical camera
 * (follows the active unit; the player can pan, rotate and zoom it).
 */
public final class ClientCombatState {
	private static final float BLEND_STEP = 0.04f; // ~1 second to fully rise

	public static boolean active;
	public static int round;
	public static int activeIndex;
	public static float moveUsed;
	public static float moveBudget;
	public static boolean actionUsed;
	public static boolean bonusUsed;
	public static List<CombatStatePayload.Entry> entries = List.of();

	// ---- tactical camera
	public static float camPitch = 55f;
	public static float camYaw;
	public static float camYawPrev;
	public static float camYawTarget;
	public static double zoom = 18.0;
	public static double zoomPrev = 18.0;
	public static double zoomTarget = 18.0;
	public static Vec3d focus = Vec3d.ZERO;
	public static Vec3d focusPrev = Vec3d.ZERO;
	/** Manual offset from the followed unit (WASD). */
	public static Vec3d pan = Vec3d.ZERO;

	private static int followedId = Integer.MIN_VALUE;
	private static boolean wasActive;
	private static float blend;
	private static float prevBlend;

	private ClientCombatState() {}

	public static void apply(CombatStatePayload p) {
		active = p.active();
		round = p.round();
		activeIndex = p.activeIndex();
		moveUsed = p.moveUsed();
		moveBudget = p.moveBudget();
		actionUsed = p.actionUsed();
		bonusUsed = p.bonusUsed();
		entries = p.entries();
		if (!active) {
			ClientGrid.clear();
		}
	}

	public static void reset() {
		active = false;
		entries = List.of();
		blend = 0;
		prevBlend = 0;
		wasActive = false;
		followedId = Integer.MIN_VALUE;
		pan = Vec3d.ZERO;
		ClientGrid.clear();
	}

	public static void tick(MinecraftClient client) {
		prevBlend = blend;
		float target = active ? 1f : 0f;
		if (blend < target) blend = Math.min(target, blend + BLEND_STEP);
		else if (blend > target) blend = Math.max(target, blend - BLEND_STEP);

		if (!active) {
			wasActive = false;
			return;
		}
		if (client.player == null || client.world == null) return;

		if (!wasActive) {
			// combat just started: begin at the player's eye / view, settle on the nearest 45 degree angle
			wasActive = true;
			Vec3d eye = client.player.getEyePos();
			focus = eye;
			focusPrev = eye;
			camYaw = client.player.getYaw();
			camYawPrev = camYaw;
			camYawTarget = Math.round(camYaw / 45f) * 45f;
			zoom = zoomPrev = zoomTarget;
			pan = Vec3d.ZERO;
			followedId = Integer.MIN_VALUE;
		}

		// follow whoever's turn it is
		int id = Integer.MIN_VALUE;
		if (activeIndex >= 0 && activeIndex < entries.size()) {
			id = entries.get(activeIndex).entityId();
		}
		if (id != followedId) {
			followedId = id;
			pan = Vec3d.ZERO;
		}
		Entity followed = id == Integer.MIN_VALUE ? null : client.world.getEntityById(id);
		Vec3d goal = followed != null ? followed.getPos().add(0, 0.9, 0).add(pan) : focus;

		focusPrev = focus;
		focus = focus.add(goal.subtract(focus).multiply(0.2));

		camYawPrev = camYaw;
		camYaw += MathHelper.wrapDegrees(camYawTarget - camYaw) * 0.25f;

		zoomPrev = zoom;
		zoom += (zoomTarget - zoom) * 0.25;
	}

	// ---- camera accessors used by the camera mixin

	/** 0 = normal camera, 1 = fully risen tactical camera (eased). */
	public static float cameraBlend(float tickDelta) {
		float t = prevBlend + (blend - prevBlend) * tickDelta;
		return t * t * (3f - 2f * t);
	}

	public static float lerpedYaw(float tickDelta) {
		return camYawPrev + MathHelper.wrapDegrees(camYaw - camYawPrev) * tickDelta;
	}

	public static Vec3d lerpedFocus(float tickDelta) {
		return focusPrev.lerp(focus, tickDelta);
	}

	public static double lerpedZoom(float tickDelta) {
		return zoomPrev + (zoom - zoomPrev) * tickDelta;
	}

	// ---- queries

	public static boolean isMyTurn() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null || activeIndex < 0 || activeIndex >= entries.size()) return false;
		return entries.get(activeIndex).entityId() == mc.player.getId();
	}

	/** The entity whose turn it is (null if unknown / not loaded). */
	public static Entity activeEntity(MinecraftClient mc) {
		if (!active || mc.world == null || activeIndex < 0 || activeIndex >= entries.size()) return null;
		return mc.world.getEntityById(entries.get(activeIndex).entityId());
	}

	/** In combat: the local player is never allowed to walk freely, all movement goes through the grid. */
	public static boolean isFrozen() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null) return false;
		int id = mc.player.getId();
		for (CombatStatePayload.Entry e : entries) {
			if (e.entityId() == id) return true;
		}
		return false;
	}
}
