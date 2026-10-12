package dev.tacticalcombat.client;

import dev.tacticalcombat.net.FloatTextPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Numbers and words that float up from a creature that was hurt, healed or gained a resource or a status. */
public final class FloatingText {
	private static final long LIFE_MS = 1500;
	private static final Random RNG = new Random();

	private static final class Item {
		int entityId;
		String text;
		int color;
		long born;
		Vec3d last;
		double side; // sideways spread so quick hits do not sit on one another
		double lift; // stacked above earlier ones that are still rising
		float size;
	}

	private static final List<Item> ITEMS = new ArrayList<>();

	private FloatingText() {}

	public static void clear() {
		ITEMS.clear();
	}

	public static void receive(FloatTextPayload p) {
		Item it = new Item();
		it.entityId = p.entityId();
		it.text = p.text();
		it.born = System.currentTimeMillis();
		it.side = (RNG.nextDouble() - 0.5) * 0.7;
		it.size = 1f;
		switch (p.kind()) {
			case 0 -> {
				it.color = damageColor(p.pct());
				it.size = 1f + 0.8f * Math.min(1f, p.pct() * 2f); // big hits are bigger
			}
			case 1 -> it.color = 0xFF55E070;
			case 2 -> it.color = p.color() | 0xFF000000;
			case 3 -> it.color = 0xFFE0B84C;
			default -> it.color = 0xFF9AA0A8;
		}
		for (Item o : ITEMS) {
			if (o.entityId == it.entityId && it.born - o.born < 600) it.lift += 0.35;
		}
		if (ITEMS.size() > 60) ITEMS.remove(0);
		ITEMS.add(it);
	}

	/** Light red for a scratch, deep dark red for half of the creature's health or more in one blow. */
	static int damageColor(float pct) {
		float t = Math.max(0f, Math.min(1f, pct * 2f));
		int r = Math.round(255 + (125 - 255) * t);
		int g = Math.round(130 + (0 - 130) * t);
		int b = Math.round(130 + (10 - 130) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	public static void render(WorldRenderContext ctx) {
		if (ITEMS.isEmpty()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		MatrixStack ms = ctx.matrixStack();
		if (mc.world == null || ms == null) return;
		long now = System.currentTimeMillis();
		Vec3d cam = ctx.camera().getPos();
		TextRenderer font = mc.textRenderer;
		VertexConsumerProvider.Immediate buffers = mc.getBufferBuilders().getEntityVertexConsumers();

		ITEMS.removeIf(i -> now - i.born > LIFE_MS);
		for (Item it : ITEMS) {
			float t = (now - it.born) / (float) LIFE_MS;
			Entity e = mc.world.getEntityById(it.entityId);
			if (e != null) it.last = new Vec3d(e.getX(), e.getY() + e.getHeight() + 0.3, e.getZ());
			if (it.last == null) continue;
			// the sideways spread is across the camera's view, so it always reads left-right
			Vec3d toCam = cam.subtract(it.last);
			Vec3d right = new Vec3d(-toCam.z, 0, toCam.x).normalize();
			double rise = 0.9 * (1 - Math.pow(1 - t, 2)) + it.lift;
			Vec3d at = it.last.add(right.multiply(it.side)).add(0, rise, 0);

			float pop = t < 0.12f ? 0.6f + 0.4f * (t / 0.12f) * 1.3f : 1f; // a small pop at the start
			float scale = 0.03f * it.size * pop;
			int alpha = t > 0.7f ? Math.max(8, Math.round(255 * (1 - (t - 0.7f) / 0.3f))) : 255;
			int color = (alpha << 24) | (it.color & 0xFFFFFF);

			ms.push();
			ms.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
			ms.multiply(ctx.camera().getRotation());
			ms.scale(-scale, -scale, scale);
			float x = -font.getWidth(it.text) / 2f;
			font.draw(it.text, x, 0, color, true, ms.peek().getPositionMatrix(), buffers,
					TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
			ms.pop();
		}
		buffers.draw();
	}
}
