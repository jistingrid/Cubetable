package dev.tacticalcombat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.tacticalcombat.grid.Grid;
import dev.tacticalcombat.net.AimPayload;
import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.sheet.AreaShape;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aiming an entry that affects several creatures. An area (sphere, cube, cone, line) follows the pointer on the grid and
 * is fixed with a click; "targets 3" lets you click up to that many creatures. Either way a panel lists who is
 * affected, with a tick you can remove, and Confirm sends the group to the server and then does the roll or use that
 * started it. Right click or Esc cancels.
 */
public final class AreaAim {
	private static AreaShape shape;
	private static String title = "";
	private static Runnable go;
	private static int casterId = -1;

	private static BlockPos hover; // the square under the pointer (area shapes)
	private static BlockPos fixed; // the square the area was fixed on
	private static final List<Integer> PICKED = new ArrayList<>(); // creatures clicked ("targets N"), more than once when the entry allows it
	private static final Set<Integer> UNTICKED = new LinkedHashSet<>(); // creatures taken out of an area

	private static final int W = 196;
	private static final int X = 8;
	private static final int Y = 70;
	private static final int ROW = 14;

	private AreaAim() {}

	public static boolean active() {
		return shape != null;
	}

	/** Starts aiming. {@code onConfirm} is what the click would have done (roll, cast, use) once the group is sent. */
	public static void begin(AreaShape s, String name, int caster, Runnable onConfirm) {
		shape = s;
		title = name;
		casterId = caster;
		go = onConfirm;
		hover = null;
		fixed = null;
		PICKED.clear();
		UNTICKED.clear();
	}

	public static void stop() {
		shape = null;
		go = null;
		hover = null;
		fixed = null;
		PICKED.clear();
		UNTICKED.clear();
	}

	// ---------------------------------------------------------------- who is affected

	private static BlockPos cellOf(Entity e) {
		return Grid.cellOf(e);
	}

	/** The area's aimed square: the fixed one, else the one under the pointer. */
	private static BlockPos aim() {
		return fixed != null ? fixed : hover;
	}

	/** Combatant entities inside the shape (area), or the ones clicked (count), minus the unticked. */
	private static List<Entity> affected(MinecraftClient mc) {
		List<Entity> out = new ArrayList<>();
		if (shape == null || mc.world == null) return out;
		Entity caster = mc.world.getEntityById(casterId);
		for (CombatStatePayload.Entry e : ClientCombatState.entries) {
			Entity en = mc.world.getEntityById(e.entityId());
			if (en == null || !en.isAlive()) continue;
			boolean in;
			if (shape.isCount()) {
				in = PICKED.contains(e.entityId());
				if (in) {
					for (int i = 0; i < java.util.Collections.frequency(PICKED, e.entityId()) - 1 && shape.repeat(); i++) out.add(en);
				}
			} else {
				BlockPos a = aim();
				if (a == null || caster == null || (shape.fromCaster() && en == caster)) continue;
				BlockPos o = cellOf(caster);
				BlockPos c = cellOf(en);
				in = shape.contains(o.getX(), o.getZ(), a.getX(), a.getZ(), c.getX(), c.getZ());
			}
			if (in && !UNTICKED.contains(e.entityId())) out.add(en);
		}
		return out;
	}

	/** Every creature the shape covers, ticked or not (the panel shows them all). */
	private static List<Entity> covered(MinecraftClient mc) {
		List<Entity> out = new ArrayList<>();
		if (shape == null || mc.world == null) return out;
		if (shape.isCount()) {
			for (int id : new LinkedHashSet<>(PICKED)) { // each creature once in the list, its repeats are shown as a number
				Entity en = mc.world.getEntityById(id);
				if (en != null) out.add(en);
			}
			return out;
		}
		Set<Integer> saved = new LinkedHashSet<>(UNTICKED);
		UNTICKED.clear();
		out = affected(mc);
		UNTICKED.addAll(saved);
		return out;
	}

	private static String nameOf(Entity e) {
		for (CombatStatePayload.Entry c : ClientCombatState.entries) {
			if (c.entityId() == e.getId() && !c.playerName().isEmpty()) return c.playerName();
		}
		return e.getName().getString();
	}

	// ---------------------------------------------------------------- per frame

	static void update(MinecraftClient mc, int mouseX, int mouseY, boolean onPanel) {
		if (shape == null) return;
		if (!ClientCombatState.active || mc.world == null) {
			stop();
			return;
		}
		if (shape.isCount() || onPanel || fixed != null) return;
		Vec3d[] ray = MousePicker.ray(mc, mouseX, mouseY);
		Vec3d hit = MousePicker.groundAt(mc, ray[0], ray[1]);
		if (hit != null) hover = new BlockPos((int) Math.floor(hit.x), (int) Math.floor(hit.y + 0.05), (int) Math.floor(hit.z));
	}

	// ---------------------------------------------------------------- the panel

	private record Btn(int x, int y, int w, int h, String label, boolean enabled, int action, int id) {}

	private static List<Btn> buttons(MinecraftClient mc, List<Entity> list) {
		List<Btn> out = new ArrayList<>();
		int y = Y + 30;
		for (Entity e : list) {
			int times = shape.isCount() ? java.util.Collections.frequency(PICKED, e.getId()) : 1;
			out.add(new Btn(X + 4, y, W - 8, ROW - 1, nameOf(e) + (times > 1 ? " x" + times : ""), true, 0, e.getId()));
			y += ROW;
		}
		int by = y + 4;
		boolean ready = !affected(mc).isEmpty() && (shape.isCount() || fixed != null);
		out.add(new Btn(X + 4, by, (W - 12) / 2, 16, "Confirm", ready, 1, 0));
		out.add(new Btn(X + 8 + (W - 12) / 2, by, (W - 12) / 2, 16, "Cancel", true, 2, 0));
		return out;
	}

	private static int height(List<Entity> list) {
		return 30 + list.size() * ROW + 4 + 16 + 6;
	}

	public static boolean contains(double mx, double my) {
		if (shape == null) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		return mx >= X - 1 && mx < X + W + 1 && my >= Y - 1 && my < Y + height(covered(mc)) + 1;
	}

	static void render(DrawContext ctx, MinecraftClient mc, TextRenderer font) {
		if (shape == null) return;
		List<Entity> list = covered(mc);
		CardStyle.frame(ctx, X, Y, W, height(list), CardStyle.STEEL);
		ctx.drawText(font, font.trimToWidth(title, W - 12), X + 6, Y + 7, CardStyle.WHITE, true);
		String hint = shape.isCount()
				? (shape.repeat() ? "Click up to " + shape.count() + " times, the same creature may repeat" : "Click up to " + shape.count() + " creatures")
						+ " (" + PICKED.size() + " picked)"
				: fixed == null ? "Point at a square, click to fix the area" : "Click another square to move it";
		CardStyle.small(ctx, font, hint, X + 6, Y + CardStyle.HEADER + 2, CardStyle.MUTED, W - 12);
		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		for (Btn b : buttons(mc, list)) {
			boolean over = b.enabled() && mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h();
			if (b.action() == 0) {
				boolean on = shape.isCount() || !UNTICKED.contains(b.id());
				ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), over ? CardStyle.BUTTON_HOVER : CardStyle.BUTTON);
				ctx.fill(b.x() + 3, b.y() + 2, b.x() + 10, b.y() + 9, CardStyle.EDGE);
				if (on) ctx.fill(b.x() + 4, b.y() + 3, b.x() + 9, b.y() + 8, 0xFFE0B84C);
				ctx.drawText(font, font.trimToWidth(b.label(), b.w() - 18), b.x() + 14, b.y() + 3, on ? CardStyle.WHITE : CardStyle.DIM, false);
			} else {
				CardStyle.button(ctx, font, b.x(), b.y(), b.w(), b.h(), b.label(), over);
				if (!b.enabled()) ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), 0xAA101218);
			}
		}
	}

	// ---------------------------------------------------------------- input

	/** A click while aiming. Always true: nothing else may act while the aim is up. */
	static boolean mouseClicked(double mx, double my, int button) {
		if (shape == null) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (button == 1) {
			stop();
			return true;
		}
		if (button != 0) return true;
		if (contains(mx, my)) {
			for (Btn b : buttons(mc, covered(mc))) {
				if (!b.enabled() || mx < b.x() || mx >= b.x() + b.w() || my < b.y() || my >= b.y() + b.h()) continue;
				switch (b.action()) {
					case 0 -> {
						if (shape.isCount()) PICKED.remove((Integer) b.id()); // one pick less (the creature is dropped with its last)
						else if (!UNTICKED.remove(b.id())) UNTICKED.add(b.id());
					}
					case 1 -> confirm(mc);
					default -> stop();
				}
				break;
			}
			return true;
		}
		if (shape.isCount()) {
			int id = ClientGrid.hoverEntity;
			if (id >= 0 && PICKED.size() < shape.count()) {
				if (shape.repeat() || !PICKED.contains(id)) PICKED.add(id);
				else PICKED.remove((Integer) id); // a different creature each time: clicking it again takes it back
			}
		} else if (hover != null) {
			if (fixed == null || !fixed.equals(hover)) UNTICKED.clear();
			fixed = hover;
		}
		return true;
	}

	static boolean keyPressed(int keyCode) {
		if (shape == null) return false;
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) stop();
		return keyCode == GLFW.GLFW_KEY_ESCAPE;
	}

	private static void confirm(MinecraftClient mc) {
		List<Integer> ids = new ArrayList<>();
		for (Entity e : affected(mc)) ids.add(e.getId());
		if (ids.isEmpty()) return;
		Runnable run = go;
		stop();
		ClientPlayNetworking.send(new AimPayload(ids, shape.separate()));
		if (run != null) run.run();
	}

	// ---------------------------------------------------------------- the area on the ground

	static void renderWorld(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (shape == null || shape.isCount() || mc.world == null) return;
		BlockPos a = aim();
		Entity caster = mc.world.getEntityById(casterId);
		MatrixStack ms = ctx.matrixStack();
		if (a == null || caster == null || ms == null) return;
		BlockPos o = cellOf(caster);
		int reach = (int) Math.ceil(shape.length()) + 2;
		int cx0 = (shape.fromCaster() ? o.getX() : a.getX()) - reach;
		int cz0 = (shape.fromCaster() ? o.getZ() : a.getZ()) - reach;
		List<Entity> hit = affected(mc);
		Vec3d cam = ctx.camera().getPos();
		ms.push();
		ms.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		boolean locked = fixed != null;
		for (int x = cx0; x <= cx0 + 2 * reach; x++) {
			for (int z = cz0; z <= cz0 + 2 * reach; z++) {
				if (!shape.contains(o.getX(), o.getZ(), a.getX(), a.getZ(), x, z)) continue;
				float y = (float) Grid.surfaceY(mc.world, new BlockPos(x, o.getY(), z)) + 0.04f;
				float al = locked ? 0.45f : 0.32f;
				quad(b, m, x + 0.04f, z + 0.04f, x + 0.96f, z + 0.96f, y, 0.95f, 0.45f, 0.15f, al);
			}
		}
		for (Entity e : hit) { // a ring under everyone who will be affected
			BlockPos c = cellOf(e);
			float y = (float) Grid.surfaceY(mc.world, c) + 0.06f;
			quad(b, m, c.getX() + 0.1f, c.getZ() + 0.1f, c.getX() + 0.9f, c.getZ() + 0.9f, y, 1f, 0.85f, 0.2f, 0.55f);
		}
		BufferRenderer.drawWithGlobalProgram(b.end());
		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
		ms.pop();
	}

	private static void quad(BufferBuilder b, Matrix4f m, float x0, float z0, float x1, float z1, float y, float r, float g, float bl, float a) {
		b.vertex(m, x0, y, z0).color(r, g, bl, a);
		b.vertex(m, x0, y, z1).color(r, g, bl, a);
		b.vertex(m, x1, y, z1).color(r, g, bl, a);
		b.vertex(m, x1, y, z0).color(r, g, bl, a);
	}
}
