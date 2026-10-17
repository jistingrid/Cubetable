package dev.tacticalcombat.client;

import dev.tacticalcombat.net.RestPayload;
import dev.tacticalcombat.net.RestPromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** The short rest popup: spend hit dice one at a time (each is rolled and heals), then Done. Esc is Done as well. */
public final class RestScreen extends Screen {
	private static RestPromptPayload state;

	private static final int W = 214;
	private static final int H = 112;

	private RestScreen() {
		super(Text.literal("Rest"));
	}

	public static void receive(RestPromptPayload p) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (p.close()) {
			state = null;
			if (mc.currentScreen instanceof RestScreen) mc.setScreen(null);
			return;
		}
		state = p;
		if (!(mc.currentScreen instanceof RestScreen)) mc.setScreen(new RestScreen());
	}

	public static void clear() {
		state = null;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private int x() {
		return (width - W) / 2;
	}

	private int y() {
		return Math.max(20, height / 3 - H / 2);
	}

	private boolean over(double mx, double my, int bx, int by, int bw) {
		return mx >= bx && mx < bx + bw && my >= by && my < by + 16;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		RestPromptPayload s = state;
		if (s == null) return;
		int x = x(), y = y();
		CardStyle.frame(ctx, x, y, W, H, CardStyle.STEEL);
		ctx.drawText(textRenderer, s.title(), x + 6, y + 7, CardStyle.WHITE, true);
		ctx.drawText(textRenderer, "Hit dice: " + s.left() + " / " + s.max() + "  (d" + s.sides() + ")", x + 8, y + CardStyle.HEADER + 6, CardStyle.WHITE, false);
		if (!s.health().isEmpty()) ctx.drawText(textRenderer, s.health(), x + 8, y + CardStyle.HEADER + 18, CardStyle.MUTED, false);
		if (!s.lastRoll().isEmpty()) ctx.drawText(textRenderer, s.lastRoll(), x + 8, y + CardStyle.HEADER + 30, 0xFF7ED08A, false);
		int by = y + H - 22;
		int bw = (W - 16) / 2;
		boolean can = s.left() > 0;
		CardStyle.button(ctx, textRenderer, x + 6, by, bw, 16, "Spend a hit die", can && over(mouseX, mouseY, x + 6, by, bw));
		if (!can) ctx.fill(x + 6, by, x + 6 + bw, by + 16, 0xAA101218);
		CardStyle.button(ctx, textRenderer, x + 10 + bw, by, bw, 16, "Done", over(mouseX, mouseY, x + 10 + bw, by, bw));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		RestPromptPayload s = state;
		if (s == null || button != 0) return super.mouseClicked(mouseX, mouseY, button);
		int x = x(), by = y() + H - 22;
		int bw = (W - 16) / 2;
		if (over(mouseX, mouseY, x + 6, by, bw) && s.left() > 0) {
			ClientPlayNetworking.send(new RestPayload(RestPayload.SPEND, ""));
			return true;
		}
		if (over(mouseX, mouseY, x + 10 + bw, by, bw)) {
			finish();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			finish();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void finish() {
		ClientPlayNetworking.send(new RestPayload(RestPayload.DONE, ""));
		state = null;
		close();
	}
}
