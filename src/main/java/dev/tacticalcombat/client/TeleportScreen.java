package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DmActionPayload;
import dev.tacticalcombat.net.DmStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dungeon Master's Teleport tool: every player online with a "Bring" button (they land on a free square next to you)
 * and a "Go" button (you land next to them), and "Bring everyone" at the top.
 */
public final class TeleportScreen extends Screen {
	private static final int W = 240;
	private static final int ROW = 18;
	private static final int ROWS = 10;

	private final List<DmStatePayload.Who> people = new ArrayList<>();
	private int left;
	private int top;
	private int scroll;

	public TeleportScreen() {
		super(Text.literal("Teleport"));
	}

	@Override
	protected void init() {
		people.clear();
		MinecraftClient mc = MinecraftClient.getInstance();
		for (DmStatePayload.Who p : DmState.players) if (mc.player == null || p.entityId() != mc.player.getId()) people.add(p);
		left = (width - W) / 2;
		top = Math.max(8, (height - (60 + ROWS * ROW)) / 2);
		addDrawableChild(ButtonWidget.builder(Text.literal("Bring everyone to me"),
				b -> ClientPlayNetworking.send(new DmActionPayload(10, 0))).dimensions(left + 6, top + 20, 130, 14).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(left + W - 56, top + 20, 50, 14).build());
	}

	@Override
	public void render(DrawContext g, int mx, int my, float delta) {
		// the list changes as people come and go: refresh it without rebuilding the buttons
		List<DmStatePayload.Who> now = new ArrayList<>();
		MinecraftClient mc = MinecraftClient.getInstance();
		for (DmStatePayload.Who p : DmState.players) if (mc.player == null || p.entityId() != mc.player.getId()) now.add(p);
		people.clear();
		people.addAll(now);
		scroll = MathHelper.clamp(scroll, 0, Math.max(0, people.size() - ROWS));

		int h = 44 + ROWS * ROW + 6;
		super.render(g, mx, my, delta);

		int ly = top + 42;
		if (people.isEmpty()) g.drawText(textRenderer, "No other players are online.", left + 8, ly + 4, 0xFF8A919C, false);
		for (int i = 0; i < ROWS; i++) {
			int idx = i + scroll;
			if (idx >= people.size()) break;
			DmStatePayload.Who p = people.get(idx);
			int y = ly + i * ROW;
			g.drawText(textRenderer, textRenderer.trimToWidth(p.name(), 110), left + 8, y + 5, 0xFFE6E8EB, false);
			small(g, mx, my, left + W - 104, y + 2, 46, "Bring", () -> ClientPlayNetworking.send(new DmActionPayload(9, p.entityId())));
			small(g, mx, my, left + W - 54, y + 2, 46, "Go", () -> ClientPlayNetworking.send(new DmActionPayload(11, p.entityId())));
		}
		g.drawText(textRenderer, "Bring: they land beside you.  Go: you land beside them.", left + 8, top + h - 10, 0xFF6F7680, false);
	}

	private final List<Runnable> pending = new ArrayList<>();
	private final List<int[]> zones = new ArrayList<>();

	private void small(DrawContext g, int mx, int my, int x, int y, int w, String label, Runnable action) {
		boolean over = mx >= x && mx < x + w && my >= y && my < y + 14;
		g.fill(x, y, x + w, y + 14, 0xFF3B414C);
		g.fill(x + 1, y + 1, x + w - 1, y + 13, over ? 0xFF2C313A : 0xFF20242B);
		g.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + 3, 0xFFFFFFFF);
		zones.add(new int[] {x, y, w});
		pending.add(action);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			for (int i = zones.size() - 1; i >= 0; i--) {
				int[] z = zones.get(i);
				if (mx >= z[0] && mx < z[0] + z[2] && my >= z[1] && my < z[1] + 14) {
					pending.get(i).run();
					return true;
				}
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll = MathHelper.clamp(scroll - (int) Math.signum(v), 0, Math.max(0, people.size() - ROWS));
		return true;
	}

	@Override
	public void renderBackground(DrawContext g, int mx, int my, float delta) {
		super.renderBackground(g, mx, my, delta);
		zones.clear(); // the buttons below are registered again while the screen is drawn
		pending.clear();
		int h = 44 + ROWS * ROW + 6;
		g.fill(left - 1, top - 1, left + W + 1, top + h + 1, 0xFF05060A);
		g.fill(left, top, left + W, top + h, 0xFF16191E);
		g.fill(left, top, left + W, top + 16, 0xFF0F1114);
		g.drawText(textRenderer, "Teleport", left + 6, top + 4, 0xFFE0B84C, false);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
