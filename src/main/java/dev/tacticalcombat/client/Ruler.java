package dev.tacticalcombat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The ruler: hold the Ruler key (Left Shift by default) during a fight and the square under the pointer becomes the
 * start of a measuring line that follows the pointer and shows its length. Left click while holding it fixes a corner
 * and carries on from there (the total adds up), right click takes the last corner back. Letting go of the key
 * clears it. Measured in squares between square centres, with the game's own rule for diagonals, and shown in the
 * game's own unit. Nothing is sent to the server.
 */
public final class Ruler {
	private static final List<Vec3d> POINTS = new ArrayList<>(); // the start and every corner fixed so far
	private static Vec3d end; // where the pointer is now (a square centre), or null
	private static boolean running;

	private Ruler() {}

	/** True while the ruler is in use (key held and a start set). */
	public static boolean active() {
		return running && !POINTS.isEmpty();
	}

	public static void stop() {
		running = false;
		POINTS.clear();
		end = null;
	}

	private static boolean keyDown(MinecraftClient mc) {
		try {
			return InputUtil.isKeyPressed(mc.getWindow().getHandle(), KeyBindingHelper.getBoundKeyOf(TacticalCombatClient.RULER_KEY).getCode());
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Called every frame by the fight screen. */
	static void update(MinecraftClient mc, int mouseX, int mouseY, boolean onPanel) {
		if (!keyDown(mc) || !ClientCombatState.active || mc.world == null) {
			if (running) stop();
			return;
		}
		Vec3d here = onPanel ? null : snapped(mc, mouseX, mouseY);
		if (here != null) end = here;
		if (!running) {
			if (here == null) return; // wait for the pointer to be over the ground
			running = true;
			POINTS.clear();
			POINTS.add(here);
		}
	}

	/** The centre of the square under the pointer, or null when the pointer is not on the ground. */
	private static Vec3d snapped(MinecraftClient mc, double mouseX, double mouseY) {
		Vec3d[] ray = MousePicker.ray(mc, mouseX, mouseY);
		Vec3d hit = MousePicker.groundAt(mc, ray[0], ray[1]);
		if (hit == null) return null;
		return new Vec3d(Math.floor(hit.x) + 0.5, hit.y, Math.floor(hit.z) + 0.5);
	}

	/** A click while the ruler is in use: left fixes a corner, right takes the last one back. True when it was used. */
	static boolean mouseClicked(int button) {
		if (!active()) return false;
		if (button == 0) {
			if (end != null && !end.equals(POINTS.get(POINTS.size() - 1))) POINTS.add(end);
		} else if (button == 1 && POINTS.size() > 1) {
			POINTS.remove(POINTS.size() - 1);
		}
		return true;
	}

	/** Squares between two square centres: the larger of the two steps with free diagonals, else the two added. */
	static int squares(Vec3d a, Vec3d b) {
		int dx = (int) Math.round(Math.abs(a.x - b.x));
		int dz = (int) Math.round(Math.abs(a.z - b.z));
		return ClientCombatState.diagonals ? Math.max(dx, dz) : dx + dz;
	}

	/** The squares of every segment, the last one running to the pointer. */
	private static List<Vec3d[]> segments() {
		List<Vec3d[]> out = new ArrayList<>();
		for (int i = 1; i < POINTS.size(); i++) out.add(new Vec3d[] {POINTS.get(i - 1), POINTS.get(i)});
		if (end != null && !end.equals(POINTS.get(POINTS.size() - 1))) out.add(new Vec3d[] {POINTS.get(POINTS.size() - 1), end});
		return out;
	}

	// ---------------------------------------------------------------- drawing

	static void drawLabels(DrawContext ctx, TextRenderer font) {
		if (!active()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		List<Vec3d[]> segs = segments();
		int total = 0;
		for (Vec3d[] s : segs) total += squares(s[0], s[1]);
		if (segs.size() > 1) {
			for (Vec3d[] s : segs) {
				Vec3d mid = s[0].add(s[1]).multiply(0.5).add(0, 0.6, 0);
				double[] p = MousePicker.project(mc, mid);
				if (p != null) label(ctx, font, ClientCombatState.distance(squares(s[0], s[1])), (int) p[0], (int) p[1], 0xFFE8E6E1);
			}
		}
		Vec3d tip = end != null ? end : POINTS.get(POINTS.size() - 1);
		double[] p = MousePicker.project(mc, tip.add(0, 1.0, 0));
		if (p != null) {
			String text = ClientCombatState.distance(total);
			label(ctx, font, segs.size() > 1 ? "Total: " + text : text, (int) p[0], (int) p[1] - 4, 0xFFF0C040);
		}
	}

	private static void label(DrawContext ctx, TextRenderer font, String text, int cx, int y, int color) {
		int w = font.getWidth(text);
		ctx.fill(cx - w / 2 - 3, y - 2, cx + w / 2 + 3, y + 10, 0xCC0A0C10);
		ctx.drawText(font, text, cx - w / 2, y, color, false);
	}

	static void render(WorldRenderContext ctx) {
		if (!active()) return;
		MatrixStack ms = ctx.matrixStack();
		if (ms == null) return;
		List<Vec3d[]> segs = segments();
		if (segs.isEmpty()) return;
		Vec3d cam = ctx.camera().getPos();
		ms.push();
		ms.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.disableDepthTest(); // a measure is read through walls and models
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		for (Vec3d[] s : segs) ribbon(b, m, s[0], s[1], 0.05);
		for (Vec3d p : POINTS) dot(b, m, p, 0.14);
		if (end != null) dot(b, m, end, 0.14);
		BufferRenderer.drawWithGlobalProgram(b.end());
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
		ms.pop();
	}

	private static void ribbon(BufferBuilder b, Matrix4f m, Vec3d a, Vec3d c, double half) {
		double dx = c.x - a.x;
		double dz = c.z - a.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-6) return;
		double px = -dz / len * half;
		double pz = dx / len * half;
		float y0 = (float) a.y + 0.08f;
		float y1 = (float) c.y + 0.08f;
		b.vertex(m, (float) (a.x + px), y0, (float) (a.z + pz)).color(1.0f, 0.82f, 0.25f, 0.95f);
		b.vertex(m, (float) (a.x - px), y0, (float) (a.z - pz)).color(1.0f, 0.82f, 0.25f, 0.95f);
		b.vertex(m, (float) (c.x - px), y1, (float) (c.z - pz)).color(1.0f, 0.82f, 0.25f, 0.95f);
		b.vertex(m, (float) (c.x + px), y1, (float) (c.z + pz)).color(1.0f, 0.82f, 0.25f, 0.95f);
	}

	private static void dot(BufferBuilder b, Matrix4f m, Vec3d p, double half) {
		float y = (float) p.y + 0.09f;
		b.vertex(m, (float) (p.x - half), y, (float) (p.z - half)).color(1.0f, 1.0f, 1.0f, 1.0f);
		b.vertex(m, (float) (p.x - half), y, (float) (p.z + half)).color(1.0f, 1.0f, 1.0f, 1.0f);
		b.vertex(m, (float) (p.x + half), y, (float) (p.z + half)).color(1.0f, 1.0f, 1.0f, 1.0f);
		b.vertex(m, (float) (p.x + half), y, (float) (p.z - half)).color(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
