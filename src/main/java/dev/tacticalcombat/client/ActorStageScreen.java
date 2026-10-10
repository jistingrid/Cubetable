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

import java.util.Optional;

/**
 * The Stage: a Dungeon Master's free cursor over the world, like Foundry's token layer. Click an Actor to select
 * it, drag it (or right-click the ground) to walk it somewhere, and use the small panel for the things you do with a
 * selected Actor: take control of it in a fight, bring it into the fight, put it away. The world stays fully visible.
 */
public final class ActorStageScreen extends Screen {
	private static boolean pendingOpen;
	/** The selected Actor's record id; kept while the screen is closed so the selection survives. */
	private static String selected = "";

	private boolean dragging;
	private Vec3d ghost;
	private int hoverEntity = -1;
	private final int[] panel = new int[4];

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

	private void moveTo(DmStatePayload.ActorInfo a, Vec3d at) {
		if (a == null || at == null || inFight(a)) return;
		ClientPlayNetworking.send(new ActorActionPayload(ActorActionPayload.MOVE, a.id(), "", "", String.format(java.util.Locale.ROOT,
				"%.2f %.2f %.2f", at.x, at.y, at.z), 0));
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
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
			String label = a.name() + (a.inFight() ? "  (fight)" : "");
			ctx.drawCenteredTextWithShadow(textRenderer, label, x, y - 16, color);
		}

		if (dragging && ghost != null) {
			double[] p = MousePicker.project(mc, ghost);
			if (p != null) ctx.drawCenteredTextWithShadow(textRenderer, "x", (int) p[0], (int) p[1] - 4, 0xFFF0C040);
		}
		drawPanel(ctx, mouseX, mouseY);
		ctx.drawTextWithShadow(textRenderer, "Click: select  |  Drag or right-click ground: move  |  Esc: leave",
				6, height - 12, 0xFF9AA0A8);
	}

	private void drawPanel(DrawContext ctx, int mouseX, int mouseY) {
		DmStatePayload.ActorInfo a = info(selected);
		panel[0] = panel[1] = panel[2] = panel[3] = -1;
		if (a == null) return;
		int w = 168;
		int h = 70;
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
		int by = y + 30;
		button(ctx, mouseX, mouseY, x + 8, by, 72, a.dmControl() ? "DM control: on" : "DM control: off", a.dmControl());
		button(ctx, mouseX, mouseY, x + 84, by, 76, "Add to fight", false);
		boolean onTurn = a.inFight() && ClientCombatState.active && !ClientCombatState.planning
				&& ClientCombatState.activeIndex >= 0 && ClientCombatState.activeIndex < ClientCombatState.entries.size()
				&& ClientCombatState.entries.get(ClientCombatState.activeIndex).entityId() == a.entityId();
		button(ctx, mouseX, mouseY, x + 8, by + 18, 72, onTurn ? "Take over" : "Recall", onTurn);
		button(ctx, mouseX, mouseY, x + 84, by + 18, 76, "Actors...", false);
	}

	private void button(DrawContext ctx, int mx, int my, int x, int y, int w, String text, boolean on) {
		boolean over = mx >= x && mx < x + w && my >= y && my < y + 16;
		ctx.fill(x, y, x + w, y + 16, on ? 0xFF6B4E12 : over ? 0xFF2C3340 : 0xFF222733);
		ctx.drawCenteredTextWithShadow(textRenderer, text, x + w / 2, y + 4, 0xFFE6E8EB);
	}

	private boolean over(double mx, double my, int x, int y, int w) {
		return mx >= x && mx < x + w && my >= y && my < y + 16;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		DmStatePayload.ActorInfo sel = info(selected);
		if (sel != null && mx >= panel[0] && mx < panel[2] && my >= panel[1] && my < panel[3]) {
			int by = panel[1] + 30;
			int x = panel[0];
			if (over(mx, my, x + 8, by, 72)) {
				ClientPlayNetworking.send(new ActorActionPayload(ActorActionPayload.CONTROL, sel.id(), "", "", "", sel.dmControl() ? 0 : 1));
			} else if (over(mx, my, x + 84, by, 76)) {
				ClientPlayNetworking.send(ActorActionPayload.of(ActorActionPayload.FIGHT, sel.id()));
			} else if (over(mx, my, x + 8, by + 18, 72)) {
				boolean onTurn = sel.inFight() && ClientCombatState.active && !ClientCombatState.planning
						&& ClientCombatState.activeIndex >= 0 && ClientCombatState.activeIndex < ClientCombatState.entries.size()
						&& ClientCombatState.entries.get(ClientCombatState.activeIndex).entityId() == sel.entityId();
				ClientPlayNetworking.send(ActorActionPayload.of(onTurn ? ActorActionPayload.TAKEOVER : ActorActionPayload.RECALL, sel.id()));
			} else if (over(mx, my, x + 84, by + 18, 76)) {
				MinecraftClient.getInstance().setScreen(new ActorsScreen(null));
			}
			return true;
		}
		if (button == 0) {
			DmStatePayload.ActorInfo hit = actorAt(mx, my);
			selected = hit == null ? "" : hit.id();
			dragging = hit != null && !hit.inFight();
			ghost = null;
			return true;
		}
		if (button == 1 && sel != null) {
			MinecraftClient mc = MinecraftClient.getInstance();
			Vec3d[] ray = MousePicker.ray(mc, mx, my);
			moveTo(sel, MousePicker.groundAt(mc, ray[0], ray[1]));
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

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (TacticalCombatClient.STAGE_KEY.matchesKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
