package dev.tacticalcombat.client;

import dev.tacticalcombat.net.ActorActionPayload;
import dev.tacticalcombat.net.DmStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The Stage: a Dungeon Master's free cursor over the world, like Foundry's token layer. Click an Actor to select
 * it, drag it (or right-click the ground) to walk it somewhere, and pose it: turn it left and right (buttons, Q / E,
 * or the mouse wheel over it), make it face you or a point (Shift + right-click), snap it to its block, nudge it with
 * the arrow keys, clone it, lock it so a stray drag cannot move it. The small panel also takes control of it in a
 * fight, brings it into the fight and puts it away. The world stays fully visible.
 */
public final class ActorStageScreen extends Screen {
	private static boolean pendingOpen;
	/** The selected Actor's record id; kept while the screen is closed so the selection survives. */
	private static String selected = "";
	/** Actors locked against moving and turning (this Dungeon Master's own safety catch). */
	private static final Set<String> LOCKED = new HashSet<>();
	/** Dragging puts an Actor in the middle of a block. */
	private static boolean snapDrag = true;

	private static final int TURN_STEP = 15;
	private static final int TURN_BIG = 90;

	private boolean dragging;
	private Vec3d ghost;
	private int hoverEntity = -1;
	private final int[] panel = new int[4];
	/** The buttons drawn this frame, so a click finds them without a second copy of the layout. */
	private final List<Btn> buttons = new ArrayList<>();

	private record Btn(int x, int y, int w, Runnable action, boolean enabled) {
		boolean at(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + 16;
		}
	}

	public ActorStageScreen() {
		super(Text.empty());
	}

	public static void requestOpen() {
		pendingOpen = true;
	}

	public static void tick(MinecraftClient client) {
		if (!pendingOpen || client.player == null) return;
		if (client.currentScreen == null) {
			pendingOpen = false;
			if (ServerCharacters.isDm()) client.setScreen(new ActorStageScreen());
			else client.player.sendMessage(Text.literal("Only a Dungeon Master has the Stage."), false);
		} else if (client.currentScreen instanceof ActorStageScreen) {
			pendingOpen = false;
			client.setScreen(null);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// nothing: the world stays fully visible
	}

	private static DmStatePayload.ActorInfo info(String id) {
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.id().equals(id)) return a;
		return null;
	}

	private Entity body(DmStatePayload.ActorInfo a) {
		MinecraftClient mc = MinecraftClient.getInstance();
		return a == null || a.entityId() < 0 || mc.world == null ? null : mc.world.getEntityById(a.entityId());
	}

	/** The placed Actor under the pixel, or null. A block in front of it hides it. */
	private DmStatePayload.ActorInfo actorAt(double mx, double my) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return null;
		Vec3d[] ray = MousePicker.ray(mc, mx, my);
		Vec3d ground = MousePicker.groundAt(mc, ray[0], ray[1]);
		double best = ground == null ? Double.MAX_VALUE : ground.distanceTo(ray[0]);
		DmStatePayload.ActorInfo found = null;
		for (DmStatePayload.ActorInfo a : DmState.actors) {
			Entity e = body(a);
			if (e == null) continue;
			Optional<Vec3d> hit = e.getBoundingBox().expand(0.2).raycast(ray[0], ray[1]);
			if (hit.isEmpty()) continue;
			double d = hit.get().distanceTo(ray[0]);
			if (d < best) {
				best = d;
				found = a;
			}
		}
		return found;
	}

	private boolean inFight(DmStatePayload.ActorInfo a) {
		return a != null && a.inFight();
	}

	private static boolean locked(DmStatePayload.ActorInfo a) {
		return a != null && LOCKED.contains(a.id());
	}

	private static void send(int op, String id, String c, int n) {
		ClientPlayNetworking.send(new ActorActionPayload(op, id, "", "", c, n));
	}

	private Vec3d snapped(Vec3d at) {
		return snapDrag ? new Vec3d(Math.floor(at.x) + 0.5, at.y, Math.floor(at.z) + 0.5) : at;
	}

	private void moveTo(DmStatePayload.ActorInfo a, Vec3d at) {
		if (a == null || at == null || inFight(a) || locked(a)) return;
		Vec3d to = snapped(at);
		send(ActorActionPayload.MOVE, a.id(), String.format(Locale.ROOT, "%.2f %.2f %.2f", to.x, to.y, to.z), 0);
	}

	private void turn(DmStatePayload.ActorInfo a, int degrees) {
		if (a == null || a.entityId() < 0 || locked(a)) return;
		send(ActorActionPayload.TURN, a.id(), "", degrees);
	}

	private void faceGround(DmStatePayload.ActorInfo a, Vec3d at) {
		if (a == null || a.entityId() < 0 || at == null || locked(a)) return;
		send(ActorActionPayload.FACE, a.id(), String.format(Locale.ROOT, "%.2f %.2f", at.x, at.z), 0);
	}

	/** Steps the selected Actor along the view: up arrow = away from the camera, right arrow = to the right of it. */
	private void nudge(DmStatePayload.ActorInfo a, int forward, int right, double step) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (a == null || a.entityId() < 0 || inFight(a) || locked(a) || mc.player == null) return;
		double yaw = Math.toRadians(mc.player.getYaw());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		if (Math.abs(fx) > Math.abs(fz)) { // snap the view to the nearest compass direction
			fx = Math.signum(fx);
			fz = 0;
		} else {
			fz = Math.signum(fz);
			fx = 0;
		}
		double dx = (fx * forward + (-fz) * right) * step;
		double dz = (fz * forward + fx * right) * step;
		send(ActorActionPayload.NUDGE, a.id(), String.format(Locale.ROOT, "%.2f 0 %.2f", dx, dz), 0);
	}

	/** Selects the next (or previous) placed Actor in the list. */
	private void cycle(int direction) {
		List<DmStatePayload.ActorInfo> placed = new ArrayList<>();
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.entityId() >= 0) placed.add(a);
		if (placed.isEmpty()) return;
		int at = -1;
		for (int i = 0; i < placed.size(); i++) if (placed.get(i).id().equals(selected)) at = i;
		selected = placed.get(Math.floorMod(at + direction, placed.size())).id();
	}

	// ---------------------------------------------------------------- drawing

	/** A dotted line with a head: where the Actor faces. */
	private void drawFacing(DrawContext ctx, MinecraftClient mc, Entity e, int color) {
		double yaw = Math.toRadians(e.getYaw());
		Vec3d from = e.getPos().add(0, 0.1, 0);
		Vec3d dir = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
		for (int i = 1; i <= 6; i++) {
			double[] p = MousePicker.project(mc, from.add(dir.multiply(0.25 * i)));
			if (p == null) return;
			int s = i == 6 ? 2 : 1;
			ctx.fill((int) p[0] - s, (int) p[1] - s, (int) p[0] + s + 1, (int) p[1] + s + 1, color);
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
		buttons.clear();
		DmStatePayload.ActorInfo hover = actorAt(mouseX, mouseY);
		hoverEntity = hover == null ? -1 : hover.entityId();

		for (DmStatePayload.ActorInfo a : DmState.actors) {
			Entity e = body(a);
			if (e == null) continue;
			boolean sel = a.id().equals(selected);
			if (!sel && a.entityId() != hoverEntity) continue;
			double[] p = MousePicker.project(mc, e.getPos().add(0, e.getHeight() + 0.55, 0));
			if (p == null) continue;
			int color = sel ? 0xFFF0C040 : 0xFFC9CCD2;
			int x = (int) p[0];
			int y = (int) p[1];
			ctx.fill(x - 4, y - 4, x + 5, y - 3, color);
			ctx.fill(x - 3, y - 3, x + 4, y - 2, color);
			ctx.fill(x - 2, y - 2, x + 3, y - 1, color);
			ctx.fill(x - 1, y - 1, x + 2, y, color);
			ctx.fill(x, y, x + 1, y + 1, color);
			String label = a.name() + (a.inFight() ? "  (fight)" : "") + (locked(a) ? "  (locked)" : "");
			ctx.drawCenteredTextWithShadow(textRenderer, label, x, y - 16, color);
			drawFacing(ctx, mc, e, color);
		}

		if (dragging && ghost != null) {
			double[] p = MousePicker.project(mc, snapped(ghost));
			if (p != null) ctx.drawCenteredTextWithShadow(textRenderer, "x", (int) p[0], (int) p[1] - 4, 0xFFF0C040);
		}
		drawToolbar(ctx, mouseX, mouseY);
		drawPanel(ctx, mouseX, mouseY);
		ctx.drawTextWithShadow(textRenderer, "Click: select  |  Drag / right-click: move  |  Shift+right-click: face  |  Q E or wheel: turn  |  Arrows: nudge  |  Tab: next  |  Del: recall  |  Esc: leave",
				6, height - 12, 0xFF9AA0A8);
	}

	/** The always-visible strip at the top left: snapping and stepping through the Actors. */
	private void drawToolbar(DrawContext ctx, int mouseX, int mouseY) {
		int x = 6;
		int y = 6;
		x += button(ctx, mouseX, mouseY, x, y, 84, "Snap drag: " + (snapDrag ? "on" : "off"), snapDrag, true, () -> snapDrag = !snapDrag) + 4;
		x += button(ctx, mouseX, mouseY, x, y, 60, "< Prev", false, true, () -> cycle(-1)) + 4;
		button(ctx, mouseX, mouseY, x, y, 60, "Next >", false, true, () -> cycle(1));
	}

	private void drawPanel(DrawContext ctx, int mouseX, int mouseY) {
		DmStatePayload.ActorInfo a = info(selected);
		panel[0] = panel[1] = panel[2] = panel[3] = -1;
		if (a == null) return;
		int w = 176;
		int h = 148;
		int x = 6;
		int y = height - 18 - h;
		panel[0] = x;
		panel[1] = y;
		panel[2] = x + w;
		panel[3] = y + h;
		ctx.fill(x, y, x + w, y + h, 0xE0151820);
		ctx.fill(x, y, x + 2, y + h, 0xFFF0C040);
		ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(a.name(), w - 12), x + 8, y + 5, 0xFFE6E8EB);
		String hp = a.max() > 0 ? Math.round(a.hp()) + "/" + Math.round(a.max()) + " HP" : "no HP";
		String where = a.entityId() < 0 ? "not placed" : a.inFight() ? "in the fight" : "placed";
		ctx.drawText(textRenderer, hp + "  -  " + where, x + 8, y + 17, 0xFF9AA0A8, false);

		boolean placed = a.entityId() >= 0;
		boolean free = placed && !locked(a);
		int ix = x + 8;
		int by = y + 30;
		// turning
		ctx.drawText(textRenderer, "Turn", ix, by + 4, 0xFF9AA0A8, false);
		int tx = ix + 28;
		tx += button(ctx, mouseX, mouseY, tx, by, 28, "<<", false, free, () -> turn(a, -TURN_BIG)) + 2;
		tx += button(ctx, mouseX, mouseY, tx, by, 28, "<", false, free, () -> turn(a, -TURN_STEP)) + 2;
		tx += button(ctx, mouseX, mouseY, tx, by, 28, ">", false, free, () -> turn(a, TURN_STEP)) + 2;
		button(ctx, mouseX, mouseY, tx, by, 28, ">>", false, free, () -> turn(a, TURN_BIG));
		by += 18;
		button(ctx, mouseX, mouseY, ix, by, 76, "Face me", false, free, () -> send(ActorActionPayload.FACE, a.id(), "", 0));
		button(ctx, mouseX, mouseY, ix + 80, by, 80, "Snap to block", false, free, () -> send(ActorActionPayload.SNAP, a.id(), "", 0));
		by += 18;
		button(ctx, mouseX, mouseY, ix, by, 76, a.dmControl() ? "DM control: on" : "DM control: off", a.dmControl(), true,
				() -> send(ActorActionPayload.CONTROL, a.id(), "", a.dmControl() ? 0 : 1));
		button(ctx, mouseX, mouseY, ix + 80, by, 80, "Add to fight", false, true, () -> send(ActorActionPayload.FIGHT, a.id(), "", 0));
		by += 18;
		boolean onTurn = a.inFight() && ClientCombatState.active && !ClientCombatState.planning
				&& ClientCombatState.activeIndex >= 0 && ClientCombatState.activeIndex < ClientCombatState.entries.size()
				&& ClientCombatState.entries.get(ClientCombatState.activeIndex).entityId() == a.entityId();
		button(ctx, mouseX, mouseY, ix, by, 76, onTurn ? "Take over" : "Recall", onTurn, true,
				() -> send(onTurn ? ActorActionPayload.TAKEOVER : ActorActionPayload.RECALL, a.id(), "", 0));
		button(ctx, mouseX, mouseY, ix + 80, by, 80, "Clone beside", false, placed, () -> send(ActorActionPayload.CLONE, a.id(), "", 0));
		by += 18;
		button(ctx, mouseX, mouseY, ix, by, 76, locked(a) ? "Locked" : "Lock", locked(a), true, () -> {
			if (!LOCKED.remove(a.id())) LOCKED.add(a.id());
		});
		button(ctx, mouseX, mouseY, ix + 80, by, 80, "Actors...", false, true, () -> {
			close();
			EncounterScreen.requestTab(2);
		});
		by += 18;
		button(ctx, mouseX, mouseY, ix, by, 160, "Possess (walk as it)", false, placed && !a.inFight(), () -> {
			send(ActorActionPayload.POSSESS, a.id(), "", 0);
			close();
		});
	}

	/** Draws a button and remembers it for clicks. Returns its width. */
	private int button(DrawContext ctx, int mx, int my, int x, int y, int w, String text, boolean on, boolean enabled, Runnable action) {
		boolean over = enabled && mx >= x && mx < x + w && my >= y && my < y + 16;
		ctx.fill(x, y, x + w, y + 16, on ? 0xFF6B4E12 : over ? 0xFF2C3340 : 0xFF222733);
		ctx.drawCenteredTextWithShadow(textRenderer, text, x + w / 2, y + 4, enabled ? 0xFFE6E8EB : 0xFF5A616C);
		buttons.add(new Btn(x, y, w, action, enabled));
		return w;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		for (Btn b : buttons) {
			if (b.at(mx, my)) {
				if (button == 0 && b.enabled()) b.action().run();
				return true;
			}
		}
		DmStatePayload.ActorInfo sel = info(selected);
		if (sel != null && mx >= panel[0] && mx < panel[2] && my >= panel[1] && my < panel[3]) return true; // the panel swallows stray clicks
		if (button == 0) {
			DmStatePayload.ActorInfo hit = actorAt(mx, my);
			selected = hit == null ? "" : hit.id();
			dragging = hit != null && !hit.inFight() && !locked(hit);
			ghost = null;
			return true;
		}
		if (button == 1 && sel != null) {
			MinecraftClient mc = MinecraftClient.getInstance();
			Vec3d[] ray = MousePicker.ray(mc, mx, my);
			Vec3d ground = MousePicker.groundAt(mc, ray[0], ray[1]);
			if (hasShiftDown()) faceGround(sel, ground);
			else moveTo(sel, ground);
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragging && button == 0) {
			MinecraftClient mc = MinecraftClient.getInstance();
			Vec3d[] ray = MousePicker.ray(mc, mx, my);
			ghost = MousePicker.groundAt(mc, ray[0], ray[1]);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (dragging && button == 0) {
			dragging = false;
			if (ghost != null) moveTo(info(selected), ghost);
			ghost = null;
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	/** The wheel over the selected Actor turns it (Shift: a quarter turn). */
	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		DmStatePayload.ActorInfo sel = info(selected);
		if (sel != null && sel.entityId() >= 0 && sel.entityId() == hoverEntity && vertical != 0) {
			turn(sel, (vertical > 0 ? -1 : 1) * (hasShiftDown() ? TURN_BIG : TURN_STEP));
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (TacticalCombatClient.STAGE_KEY.matchesKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		DmStatePayload.ActorInfo sel = info(selected);
		boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
		switch (keyCode) {
			case GLFW.GLFW_KEY_Q -> turn(sel, -(shift ? TURN_BIG : TURN_STEP));
			case GLFW.GLFW_KEY_E -> turn(sel, shift ? TURN_BIG : TURN_STEP);
			case GLFW.GLFW_KEY_UP -> nudge(sel, 1, 0, shift ? 0.5 : 1.0);
			case GLFW.GLFW_KEY_DOWN -> nudge(sel, -1, 0, shift ? 0.5 : 1.0);
			case GLFW.GLFW_KEY_LEFT -> nudge(sel, 0, -1, shift ? 0.5 : 1.0);
			case GLFW.GLFW_KEY_RIGHT -> nudge(sel, 0, 1, shift ? 0.5 : 1.0);
			case GLFW.GLFW_KEY_TAB -> cycle(shift ? -1 : 1);
			case GLFW.GLFW_KEY_DELETE -> {
				if (sel != null && sel.entityId() >= 0 && !sel.inFight() && !locked(sel)) send(ActorActionPayload.RECALL, sel.id(), "", 0);
			}
			default -> {
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
		}
		return true;
	}
}
