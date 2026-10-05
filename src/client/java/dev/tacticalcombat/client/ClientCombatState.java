package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import net.minecraft.client.MinecraftClient;

import java.util.List;

/** Client side mirror of the combat the local player is in, plus the camera blend animation. */
public final class ClientCombatState {
	/** Pitch (degrees) the view eases to when combat starts, so the player looks down at the battlefield. */
	private static final float TACTICAL_PITCH = 50.0f;
	private static final float BLEND_STEP = 0.04f; // ~1 second to fully rise

	public static boolean active;
	public static int round;
	public static int activeIndex;
	public static float moveUsed;
	public static float moveBudget;
	public static boolean actionUsed;
	public static boolean bonusUsed;
	public static List<CombatStatePayload.Entry> entries = List.of();

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
	}

	public static void reset() {
		active = false;
		entries = List.of();
		blend = 0;
		prevBlend = 0;
	}

	public static void tick(MinecraftClient client) {
		prevBlend = blend;
		float target = active ? 1f : 0f;
		if (blend < target) blend = Math.min(target, blend + BLEND_STEP);
		else if (blend > target) blend = Math.max(target, blend - BLEND_STEP);

		// While the camera rises, tilt the player's view down so the crosshair stays on what you see.
		if (active && blend < 1f && client.player != null) {
			float pitch = client.player.getPitch();
			if (pitch < TACTICAL_PITCH) {
				client.player.setPitch(pitch + (TACTICAL_PITCH - pitch) * 0.15f);
			}
		}
	}

	/** 0 = normal camera, 1 = fully risen tactical camera (eased). */
	public static float cameraBlend(float tickDelta) {
		float t = prevBlend + (blend - prevBlend) * tickDelta;
		return t * t * (3f - 2f * t);
	}

	public static boolean isTacticalCameraOn() {
		return blend > 0.001f || prevBlend > 0.001f;
	}

	public static boolean isMyTurn() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null || activeIndex < 0 || activeIndex >= entries.size()) return false;
		return entries.get(activeIndex).entityId() == mc.player.getId();
	}

	/** In combat but waiting for someone else: the player may not move at all. */
	public static boolean isFrozen() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null) return false;
		int id = mc.player.getId();
		boolean inList = false;
		for (CombatStatePayload.Entry e : entries) {
			if (e.entityId() == id) {
				inList = true;
				break;
			}
		}
		return inList && !isMyTurn();
	}

	/** My turn, but the movement budget is spent. */
	public static boolean isOutOfMovement() {
		return isMyTurn() && moveBudget - moveUsed < 0.05f;
	}
}
