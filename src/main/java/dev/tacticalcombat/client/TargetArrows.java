package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An arrow over every targeted creature. Your own target gets a coloured arrow, other players' targets a gray one;
 * when several arrows point at the same creature they sit side by side so each stays visible.
 */
public final class TargetArrows {
	private static final int MINE = 0xFF4DA3FF;
	private static final int OTHERS = 0xFF9A9A9A;
	private static final int OUTLINE = 0xFF000000;
	private static final int SPACING = 11;

	private TargetArrows() {}

	public static void render(DrawContext ctx, MinecraftClient mc, float tickDelta) {
		if (!ClientCombatState.active || ClientCombatState.targets.isEmpty() || mc.world == null || mc.player == null) return;

		// group the arrows by target, your own first so it is the one in the middle-left of a crowd
		Map<Integer, List<Integer>> byTarget = new LinkedHashMap<>();
		for (CombatStatePayload.Target t : ClientCombatState.targets) {
			byTarget.computeIfAbsent(t.at(), k -> new ArrayList<>()).add(t.by());
		}
		long time = mc.world.getTime();
		for (Map.Entry<Integer, List<Integer>> e : byTarget.entrySet()) {
			Entity target = mc.world.getEntityById(e.getKey());
			if (target == null) continue;
			List<Integer> by = e.getValue();
			by.sort((a, b) -> Boolean.compare(b == mc.player.getId(), a == mc.player.getId()));

			Vec3d head = target.getLerpedPos(tickDelta).add(0, target.getHeight() + 0.45, 0);
			double[] screen = MousePicker.project(mc, head);
			if (screen == null) continue;
			int bob = Math.round((float) Math.sin((time + tickDelta) * 0.25) * 2f);
			int n = by.size();
			for (int i = 0; i < n; i++) {
				int x = (int) Math.round(screen[0]) + Math.round((i - (n - 1) / 2f) * SPACING);
				int y = (int) Math.round(screen[1]) + bob;
				arrow(ctx, x, y, by.get(i) == mc.player.getId() ? MINE : OTHERS);
			}
		}
	}

	/** A downward arrow, tip at (x, y): 3 wide shaft above a 9 wide head, with a dark outline. */
	private static void arrow(DrawContext ctx, int x, int y, int color) {
		// head: rows narrowing to the tip
		for (int row = 0; row < 5; row++) {
			int half = 4 - row;
			ctx.fill(x - half - 1, y - 5 + row, x + half + 2, y - 4 + row, OUTLINE);
		}
		ctx.fill(x - 2, y - 13, x + 3, y - 4, OUTLINE);
		for (int row = 0; row < 5; row++) {
			int half = 4 - row;
			ctx.fill(x - half, y - 5 + row, x + half + 1, y - 4 + row, color);
		}
		ctx.fill(x - 1, y - 12, x + 2, y - 5, color);
	}
}
