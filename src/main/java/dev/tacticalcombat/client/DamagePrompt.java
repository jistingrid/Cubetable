package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DamageChoicePayload;
import dev.tacticalcombat.net.DamagePromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The small popup that asks the owner of a target what to do with damage: Full, Half, Heal or Custom (which asks for
 * a number and then Damage or Heal). Prompts wait in a queue and show one at a time, after the dice have stopped.
 */
public final class DamagePrompt {
	private record Entry(long id, String target, String attacker, String label, int amount) {}

	private static final List<Entry> QUEUE = new ArrayList<>();
	private static boolean custom;
	private static final StringBuilder TYPED = new StringBuilder();

	private static final int W = 214;
	private static final int PANEL = 0xF01D2029;
	private static final int EDGE = 0xFFE0B84C;
	private static final int BUTTON = 0xFF252936;
	private static final int BUTTON_EDGE = 0xFF3A3F4D;

	private DamagePrompt() {}

	public static void receive(DamagePromptPayload p) {
		if (p.close()) {
			QUEUE.removeIf(e -> e.id() == p.id());
			if (QUEUE.isEmpty()) reset();
			return;
		}
		QUEUE.add(new Entry(p.id(), p.target(), p.attacker(), p.label(), p.amount()));
	}

	public static void clear() {
		QUEUE.clear();
		reset();
	}

	private static void reset() {
		custom = false;
		TYPED.setLength(0);
	}

	/** A prompt is up (the dice have finished). */
	public static boolean visible() {
		return !QUEUE.isEmpty() && !DiceAnimation.busy();
	}

	/** Something is waiting, whether or not it is shown yet. */
	public static boolean pending() {
		return !QUEUE.isEmpty();
	}

	public static long firstId() {
		return QUEUE.isEmpty() ? 0 : QUEUE.get(0).id();
	}

	// ------------------------------------------------------------------ layout

	/** x, y, w, h of the panel. */
	private static int[] panel(int screenW) {
		int h = custom ? 74 : 62;
		return new int[] {(screenW - W) / 2, 78, W, h};
	}

	private static int[][] buttons(int[] p) {
		int count = custom ? 3 : 4;
		int gap = 4;
		int bw = (W - 12 - gap * (count - 1)) / count;
		int[][] out = new int[count][];
		int by = p[1] + p[3] - 22;
		for (int i = 0; i < count; i++) out[i] = new int[] {p[0] + 6 + i * (bw + gap), by, bw, 16};
		return out;
	}

	// ------------------------------------------------------------------ drawing

	public static void render(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW) {
		if (!visible()) return;
		Entry e = QUEUE.get(0);
		int[] p = panel(screenW);
		ctx.fill(p[0] - 1, p[1] - 1, p[0] + p[2] + 1, p[1] + p[3] + 1, EDGE);
		ctx.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], PANEL);

		ctx.drawCenteredTextWithShadow(font, Text.translatable("tacticalcombat.prompt.takes", e.target(), e.amount()),
				p[0] + p[2] / 2, p[1] + 6, 0xFFFFFFFF);
		String from = e.attacker() + (e.label().isEmpty() ? "" : " - " + e.label());
		ctx.drawCenteredTextWithShadow(font, font.trimToWidth(from, p[2] - 12), p[0] + p[2] / 2, p[1] + 18, 0xFF9AA0AE);
		if (QUEUE.size() > 1) {
			String more = "+" + (QUEUE.size() - 1);
			ctx.drawTextWithShadow(font, more, p[0] + p[2] - font.getWidth(more) - 4, p[1] + 4, 0xFF9AA0AE);
		}

		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		if (custom) {
			int fx = p[0] + 6;
			int fy = p[1] + 30;
			ctx.fill(fx - 1, fy - 1, fx + p[2] - 11, fy + 13, BUTTON_EDGE);
			ctx.fill(fx, fy, fx + p[2] - 12, fy + 12, 0xFF0F1117);
			boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
			ctx.drawTextWithShadow(font, TYPED + (caret ? "_" : ""), fx + 3, fy + 2, 0xFFFFFFFF);
		}
		String[] labels = custom
				? new String[] {Text.translatable("tacticalcombat.prompt.damage").getString(),
						Text.translatable("tacticalcombat.prompt.heal").getString(),
						Text.translatable("tacticalcombat.prompt.back").getString()}
				: new String[] {Text.translatable("tacticalcombat.prompt.full").getString(),
						Text.translatable("tacticalcombat.prompt.half").getString(),
						Text.translatable("tacticalcombat.prompt.heal").getString(),
						Text.translatable("tacticalcombat.prompt.custom").getString()};
		int[][] btns = buttons(p);
		for (int i = 0; i < btns.length; i++) {
			int[] b = btns[i];
			boolean over = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
			ctx.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], BUTTON_EDGE);
			ctx.fill(b[0] + 1, b[1] + 1, b[0] + b[2] - 1, b[1] + b[3] - 1, over ? 0xFF343A4C : BUTTON);
			ctx.drawCenteredTextWithShadow(font, labels[i], b[0] + b[2] / 2, b[1] + 4, 0xFFFFFFFF);
		}
	}

	// ------------------------------------------------------------------ input

	public static boolean contains(double mx, double my, int screenW) {
		if (!visible()) return false;
		int[] p = panel(screenW);
		return mx >= p[0] - 1 && mx < p[0] + p[2] + 1 && my >= p[1] - 1 && my < p[1] + p[3] + 1;
	}

	public static boolean mouseClicked(double mx, double my, int button, int screenW) {
		if (!contains(mx, my, screenW)) return false;
		if (button != 0) return true;
		int[][] btns = buttons(panel(screenW));
		for (int i = 0; i < btns.length; i++) {
			int[] b = btns[i];
			if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
				press(i);
				break;
			}
		}
		return true;
	}

	private static void press(int index) {
		if (!custom) {
			switch (index) {
				case 0 -> answer(0, 0);
				case 1 -> answer(1, 0);
				case 2 -> answer(2, 0);
				default -> custom = true;
			}
			return;
		}
		switch (index) {
			case 0 -> answerCustom(3);
			case 1 -> answerCustom(4);
			default -> reset();
		}
	}

	private static void answerCustom(int mode) {
		if (TYPED.length() == 0) return;
		int amount;
		try {
			amount = Integer.parseInt(TYPED.toString());
		} catch (NumberFormatException e) {
			return;
		}
		answer(mode, amount);
	}

	private static void answer(int mode, int amount) {
		if (QUEUE.isEmpty()) return;
		Entry e = QUEUE.remove(0);
		ClientPlayNetworking.send(new DamageChoicePayload(e.id(), mode, amount));
		reset();
	}

	public static boolean charTyped(char chr) {
		if (!visible() || !custom) return false;
		if (chr >= '0' && chr <= '9' && TYPED.length() < 4) TYPED.append(chr);
		return true;
	}

	public static boolean keyPressed(int keyCode) {
		if (!visible() || !custom) return false;
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE && TYPED.length() > 0) TYPED.setLength(TYPED.length() - 1);
		else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) answerCustom(3);
		else if (keyCode == GLFW.GLFW_KEY_ESCAPE) reset();
		return true;
	}
}
