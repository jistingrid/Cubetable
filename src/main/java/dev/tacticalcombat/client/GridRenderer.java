package dev.tacticalcombat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.tacticalcombat.grid.Grid;
import dev.tacticalcombat.net.GridPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the tactical overlay on the ground: blue = squares you can walk to, red = squares enemies can reach
 * or hit, a white start square, a bright cursor square and a line previewing the path to the hovered square.
 *
 * <p>Drawn in two passes: normally (depth tested), and then once more for squares that are only hidden by
 * faded (semi-transparent) blocks, without depth test, because faded blocks still write depth and would
 * otherwise hide the squares behind them.
 */
public final class GridRenderer {
	private static final float LIFT = 0.03f;
	private static int quads;

	private GridRenderer() {}

	public static void render(WorldRenderContext ctx) {
		if (!ClientCombatState.active) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		ClientWorld world = mc.world;
		MatrixStack ms = ctx.matrixStack();
		if (world == null || ms == null) return;

		List<GridPayload.Cell> cells = ClientGrid.cells;
		if (cells.isEmpty() && ClientGrid.hoverEntity < 0) return;

		Vec3d cam = ctx.camera().getPos();
		ms.push();
		ms.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f m = ms.peek().getPositionMatrix();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		RenderSystem.enableDepthTest();
		pass(m, world, cells, false);

		RenderSystem.disableDepthTest();
		pass(m, world, cells, true);

		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
		ms.pop();
	}

	/** One draw call of everything whose "see-through" state equals {@code seeThrough}. */
	private static void pass(Matrix4f m, ClientWorld world, List<GridPayload.Cell> cells, boolean seeThrough) {
		BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		quads = 0;

		// enemy threat (red); squares you can walk to are drawn blue on top
		for (long key : ClientGrid.threat) {
			if (ClientGrid.indexOf(key) != null || BlockFade.isSeeThrough(key) != seeThrough) continue;
			tile(b, m, world, key, 0.04f, 1.00f, 0.35f, 0.10f, shade(key, 0.36f));
		}

		// walkable squares (blue, slightly checkered like the reference)
		for (GridPayload.Cell c : cells) {
			if (BlockFade.isSeeThrough(c.pos()) != seeThrough) continue;
			if (c.cost() == 0) {
				tile(b, m, world, c.pos(), 0.04f, 1.0f, 1.0f, 1.0f, 0.32f); // start square
			} else if (c.endable()) {
				tile(b, m, world, c.pos(), 0.04f, 0.20f, 0.42f, 1.00f, shade(c.pos(), 0.40f));
			}
		}

		int hover = ClientGrid.hover;
		if (hover >= 0 && hover < cells.size()) {
			List<Integer> path = ClientGrid.pathTo(hover);
			for (int i = 1; i < path.size(); i++) {
				long from = cells.get(path.get(i - 1)).pos();
				long to = cells.get(path.get(i)).pos();
				boolean through = BlockFade.isSeeThrough(from) || BlockFade.isSeeThrough(to);
				if (through == seeThrough) drawPathSegment(b, m, world, from, to);
			}

			long key = cells.get(hover).pos();
			if (BlockFade.isSeeThrough(key) == seeThrough) {
				tile(b, m, world, key, 0.04f, 0.65f, 0.90f, 1.00f, 0.55f);
				border(b, m, world, key, 0.04f, 0.09f, 0.85f, 0.95f, 1.00f, 1.00f);
			}
		}

		if (!seeThrough && ClientGrid.hoverEntity >= 0) {
			Entity target = world.getEntityById(ClientGrid.hoverEntity);
			if (target != null) {
				long key = Grid.cellOf(target).asLong();
				tile(b, m, world, key, 0.02f, 1.0f, 0.15f, 0.15f, 0.40f);
				border(b, m, world, key, 0.02f, 0.10f, 1.0f, 0.25f, 0.25f, 1.0f);
			}
		}

		if (quads == 0) {
			degenerate(b, m); // the buffer must contain at least one vertex to be ended
		}
		BufferRenderer.drawWithGlobalProgram(b.end());
	}

	// ---------------------------------------------------------------- shapes

	private static float shade(long key, float base) {
		BlockPos p = BlockPos.fromLong(key);
		return ((p.getX() + p.getZ()) & 1) == 0 ? base : base - 0.07f;
	}

	/** A filled square on top of a grid square. */
	private static void tile(BufferBuilder b, Matrix4f m, ClientWorld world, long key, float inset,
							 float r, float g, float bl, float a) {
		BlockPos p = BlockPos.fromLong(key);
		float y = ClientGrid.surface(world, key) + LIFT;
		rect(b, m, p.getX() + inset, p.getZ() + inset, p.getX() + 1 - inset, p.getZ() + 1 - inset, y, r, g, bl, a);
	}

	/** A frame around a grid square. */
	private static void border(BufferBuilder b, Matrix4f m, ClientWorld world, long key, float inset, float width,
							   float r, float g, float bl, float a) {
		BlockPos p = BlockPos.fromLong(key);
		float y = ClientGrid.surface(world, key) + LIFT + 0.01f;
		float x0 = p.getX() + inset;
		float z0 = p.getZ() + inset;
		float x1 = p.getX() + 1 - inset;
		float z1 = p.getZ() + 1 - inset;
		rect(b, m, x0, z0, x1, z0 + width, y, r, g, bl, a);
		rect(b, m, x0, z1 - width, x1, z1, y, r, g, bl, a);
		rect(b, m, x0, z0 + width, x0 + width, z1 - width, y, r, g, bl, a);
		rect(b, m, x1 - width, z0 + width, x1, z1 - width, y, r, g, bl, a);
	}

	private static void drawPathSegment(BufferBuilder b, Matrix4f m, ClientWorld world, long from, long to) {
		BlockPos a = BlockPos.fromLong(from);
		BlockPos c = BlockPos.fromLong(to);
		float y0 = ClientGrid.surface(world, from) + LIFT + 0.02f;
		float y1 = ClientGrid.surface(world, to) + LIFT + 0.02f;
		line(b, m, a.getX() + 0.5, y0, a.getZ() + 0.5, c.getX() + 0.5, y1, c.getZ() + 0.5,
				0.07, 0.35f, 0.95f, 1.0f, 0.95f);
	}

	private static void rect(BufferBuilder b, Matrix4f m, float x0, float z0, float x1, float z1, float y,
							 float r, float g, float bl, float a) {
		b.vertex(m, x0, y, z0).color(r, g, bl, a);
		b.vertex(m, x0, y, z1).color(r, g, bl, a);
		b.vertex(m, x1, y, z1).color(r, g, bl, a);
		b.vertex(m, x1, y, z0).color(r, g, bl, a);
		quads++;
	}

	/** A flat ribbon between two points, extended by its half width so corners join up. */
	private static void line(BufferBuilder b, Matrix4f m, double x0, double y0, double z0, double x1, double y1, double z1,
							 double halfWidth, float r, float g, float bl, float a) {
		double dx = x1 - x0;
		double dz = z1 - z0;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-6) return;
		double ux = dx / len;
		double uz = dz / len;
		double px = -uz * halfWidth;
		double pz = ux * halfWidth;
		double ex = ux * halfWidth;
		double ez = uz * halfWidth;
		x0 -= ex;
		z0 -= ez;
		x1 += ex;
		z1 += ez;
		b.vertex(m, (float) (x0 + px), (float) y0, (float) (z0 + pz)).color(r, g, bl, a);
		b.vertex(m, (float) (x0 - px), (float) y0, (float) (z0 - pz)).color(r, g, bl, a);
		b.vertex(m, (float) (x1 - px), (float) y1, (float) (z1 - pz)).color(r, g, bl, a);
		b.vertex(m, (float) (x1 + px), (float) y1, (float) (z1 + pz)).color(r, g, bl, a);
		quads++;
	}

	private static void degenerate(BufferBuilder b, Matrix4f m) {
		for (int i = 0; i < 4; i++) {
			b.vertex(m, 0f, 0f, 0f).color(0f, 0f, 0f, 0f);
		}
	}
}
