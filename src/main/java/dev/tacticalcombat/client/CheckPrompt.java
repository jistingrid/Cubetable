package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CheckChoicePayload;
import dev.tacticalcombat.net.CheckPromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The small popup that asks the owner of a creature to roll a check somebody requested (a saving throw ...): Roll,
 * Advantage or Disadvantage. Requests queue and show one at a time, after the dice have stopped; it sits under the
 * damage prompt when both are up.
 */
public final class CheckPrompt {
	private record Entry(long id, String target, String requester, String label, int dc) {}

	private static final List<Entry> QUEUE = new ArrayList<>();
	private static final int W = 214;
	private static final int H = 66;

	private CheckPrompt() {}

	public static void receive(CheckPromptPayload p) {
		if (p.close()) {
			QUEUE.removeIf(e -> e.id() == p.id());
			return;
		}
		QUEUE.add(new Entry(p.id(), p.target(), p.requester(), p.label(), p.dc()));
	}

	public static void clear() {
		QUEUE.clear();
	}

	public static boolean visible() {
		return !QUEUE.isEmpty() && !DiceAnimation.busy();
	}

	public static boolean pending() {
		return !QUEUE.isEmpty();
	}

	public static long firstId() {
		return QUEUE.isEmpty() ? 0 : QUEUE.get(0).id();
	}

	private static int[] panel(int screenW) {
		return new int[] {(screenW - W) / 2, DamagePrompt.visible() ? 150 : 78, W, H};
	}

	private static int[][] buttons(int[] p) {
		int gap = 4;
		int bw = (W - 12 - gap * 2) / 3;
		int[][] out = new int[3][];
		for (int i = 0; i < 3; i++) out[i] = new int[] {p[0] + 6 + i * (bw + gap), p[1] + p[3] - 22, bw, 16};
		return out;
	}

	public static void render(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW) {
		if (!visible()) return;
		Entry e = QUEUE.get(0);
		int[] p = panel(screenW);
		CardStyle.frame(ctx, p[0], p[1], p[2], p[3], CardStyle.STEEL);
		ctx.drawText(font, font.trimToWidth(e.target() + ": " + e.label() + (e.dc() >= 0 ? "  (DC " + e.dc() + ")" : ""), p[2] - 28),
				p[0] + 6, p[1] + 7, CardStyle.WHITE, true);
		String from = e.requester().isEmpty() ? "Roll requested" : e.requester() + " asks for a roll";
		CardStyle.small(ctx, font, from, p[0] + 6, p[1] + CardStyle.HEADER + 4, CardStyle.MUTED, p[2] - 12);
		if (QUEUE.size() > 1) {
			String more = "+" + (QUEUE.size() - 1);
			ctx.drawText(font, more, p[0] + p[2] - font.getWidth(more) - 5, p[1] + 7, CardStyle.MUTED, true);
		}
		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		String[] labels = {"Roll", "Advantage", "Disadvantage"};
		int[][] btns = buttons(p);
		for (int i = 0; i < btns.length; i++) {
			int[] b = btns[i];
			boolean over = mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
			CardStyle.button(ctx, font, b[0], b[1], b[2], b[3], labels[i], over);
		}
	}

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
				Entry e = QUEUE.remove(0);
				ClientPlayNetworking.send(new CheckChoicePayload(e.id(), i));
				break;
			}
		}
		return true;
	}
}
