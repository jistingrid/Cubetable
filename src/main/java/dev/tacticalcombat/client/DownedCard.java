package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DownedActionPayload;
import dev.tacticalcombat.net.DownedPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is lying on the ground (every client: the model is drawn flat) and the card the people who have to answer see:
 * a player the death save of their own character, a Dungeon Master all of them, with buttons to stabilize, kill or
 * revive. The card is drawn in the style of the shown-entry cards ({@link CardStyle}).
 */
public final class DownedCard {
	private static final Map<Integer, DownedPayload> DOWN = new HashMap<>();
	private static final List<DownedPayload> CARDS = new ArrayList<>();

	private static final int W = 214;
	private static int waitingId = -1;
	private static long waitingUntil;

	private DownedCard() {}

	public static void receive(DownedPayload p) {
		if (waitingId == p.entityId()) waitingId = -1;
		if (p.status() == 3) {
			DOWN.remove(p.entityId());
			CARDS.removeIf(c -> c.entityId() == p.entityId());
			return;
		}
		DOWN.put(p.entityId(), p);
		for (int i = 0; i < CARDS.size(); i++) {
			if (CARDS.get(i).entityId() == p.entityId()) {
				CARDS.set(i, p);
				return;
			}
		}
		if (p.prompt()) CARDS.add(p);
	}

	public static void clear() {
		DOWN.clear();
		CARDS.clear();
	}

	/** Lying on the ground (down, stable or dead). */
	public static boolean isDown(int entityId) {
		return DOWN.containsKey(entityId);
	}

	/** This client's own player is down: they do not walk. */
	public static boolean selfDown() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player != null && DOWN.containsKey(mc.player.getId());
	}

	public static boolean visible() {
		return !CARDS.isEmpty() && !DiceAnimation.busy();
	}

	/** Changes whenever a different card comes to the front (for screens that open themselves for it). */
	public static long key() {
		return CARDS.isEmpty() ? 0 : CARDS.get(0).entityId() * 31L + CARDS.get(0).status() * 7L + CARDS.get(0).successes() * 3L + CARDS.get(0).failures();
	}

	// ------------------------------------------------------------------ layout

	private static List<String> buttons(DownedPayload c) {
		List<String> out = new ArrayList<>();
		if (c.canRoll()) out.add(c.saveLabel().isEmpty() ? "Roll" : "Roll " + c.saveLabel().toLowerCase());
		if (c.dm() && c.status() != 2) {
			if (c.status() == 0) out.add("Stable");
			out.add("Kill");
		}
		if (c.dm()) out.add("Revive");
		if (!c.canRoll()) out.add("OK");
		return out;
	}

	private static int height(DownedPayload c) {
		int pips = c.needSuccesses() > 0 ? 24 : 0;
		return CardStyle.HEADER + 18 + pips + (c.note().isEmpty() ? 0 : 11) + 26;
	}

	private static int[] box(int screenW, DownedPayload c) {
		int y = DamagePrompt.visible() ? 150 : 28;
		return new int[] {(screenW - W) / 2, y, W, height(c)};
	}

	private static int[][] buttonBoxes(int[] b, int count) {
		int gap = 4;
		int bw = (W - 12 - gap * (count - 1)) / count;
		int[][] out = new int[count][];
		for (int i = 0; i < count; i++) out[i] = new int[] {b[0] + 6 + i * (bw + gap), b[1] + b[3] - 22, bw, 16};
		return out;
	}

	// ------------------------------------------------------------------ drawing

	public static void render(DrawContext g, MinecraftClient mc, TextRenderer tr, int screenW) {
		if (!visible()) return;
		DownedPayload c = CARDS.get(0);
		int[] b = box(screenW, c);
		int[] banner = c.status() == 0 ? CardStyle.RED : c.status() == 1 ? CardStyle.STEEL : CardStyle.GREY;
		CardStyle.frame(g, b[0], b[1], b[2], b[3], banner);

		String state = c.status() == 2 ? "is dead" : c.status() == 1 ? "is stable" : "is " + c.label().toLowerCase();
		g.drawText(tr, tr.trimToWidth(c.name() + " " + state, b[2] - 28), b[0] + 6, b[1] + 7, CardStyle.WHITE, true);
		if (CARDS.size() > 1) g.drawText(tr, "+" + (CARDS.size() - 1), b[0] + b[2] - 18, b[1] + 7, CardStyle.MUTED, true);

		int y = b[1] + CardStyle.HEADER + 4;
		String line = c.status() == 2 ? "Nothing more to roll." : c.status() == 1 ? "Out of danger, still unconscious."
				: c.canRoll() ? "Roll the " + c.saveLabel().toLowerCase() + "." : c.needSuccesses() > 0 ? "Waiting for the " + c.saveLabel().toLowerCase() + "." : "Waiting for help.";
		CardStyle.small(g, tr, line, b[0] + 6, y, CardStyle.MUTED, b[2] - 12);
		y += 12;
		if (c.needSuccesses() > 0) {
			pips(g, tr, "Successes", b[0] + 6, y, c.successes(), c.needSuccesses(), 0xFF50D060);
			pips(g, tr, "Failures", b[0] + 6, y + 10, c.failures(), c.needFailures(), 0xFFE04040);
			y += 22;
		}
		if (!c.note().isEmpty()) CardStyle.small(g, tr, c.note(), b[0] + 6, y, CardStyle.DIM, b[2] - 12);

		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		List<String> labels = buttons(c);
		int[][] boxes = buttonBoxes(b, labels.size());
		for (int i = 0; i < boxes.length; i++) {
			int[] r = boxes[i];
			boolean over = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
			CardStyle.button(g, tr, r[0], r[1], r[2], r[3], labels.get(i), over);
		}
	}

	private static void pips(DrawContext g, TextRenderer tr, String label, int x, int y, int have, int need, int color) {
		CardStyle.small(g, tr, label, x, y, CardStyle.DIM, 60);
		for (int i = 0; i < need; i++) {
			int px = x + 56 + i * 10;
			g.fill(px, y, px + 7, y + 7, CardStyle.EDGE);
			g.fill(px + 1, y + 1, px + 6, y + 6, i < have ? color : 0xFF0F1117);
		}
	}

	// ------------------------------------------------------------------ input

	public static boolean contains(double mx, double my, int screenW) {
		if (!visible()) return false;
		int[] b = box(screenW, CARDS.get(0));
		return mx >= b[0] - 1 && mx < b[0] + b[2] + 1 && my >= b[1] - 1 && my < b[1] + b[3] + 1;
	}

	public static boolean mouseClicked(double mx, double my, int button, int screenW) {
		if (!contains(mx, my, screenW)) return false;
		if (button != 0) return true;
		DownedPayload c = CARDS.get(0);
		List<String> labels = buttons(c);
		int[][] boxes = buttonBoxes(box(screenW, c), labels.size());
		for (int i = 0; i < boxes.length; i++) {
			int[] r = boxes[i];
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
				press(c, labels.get(i));
				break;
			}
		}
		return true;
	}

	private static void press(DownedPayload c, String label) {
		if (label.equals("OK")) {
			CARDS.remove(0);
			return;
		}
		int op = label.startsWith("Roll") ? 0 : label.equals("Stable") ? 1 : label.equals("Kill") ? 2 : 3;
		if (op == 0) { // one roll at a time: the answer (a new card state) lifts the guard, so does a few seconds' wait
			long now = System.currentTimeMillis();
			if (waitingId == c.entityId() && now < waitingUntil) return;
			waitingId = c.entityId();
			waitingUntil = now + 3000;
		}
		ClientPlayNetworking.send(new DownedActionPayload(c.entityId(), op));
	}
}
