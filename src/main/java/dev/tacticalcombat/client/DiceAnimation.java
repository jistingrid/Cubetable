package dev.tacticalcombat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.net.DiceRollPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * 2D dice roll overlay: the dice tumble across the screen (not in the 3D world), settle on the server's result,
 * and the result is then printed in the chat. Rolls that arrive while one is playing wait in a queue.
 */
public final class DiceAnimation {
	private static final int TUMBLE_TICKS = 40;
	private static final int HOLD_TICKS = 40;

	private static final ArrayDeque<DiceRollPayload> QUEUE = new ArrayDeque<>();
	private static DiceRollPayload current;
	private static int age;
	private static boolean printed;
	private static Random random = new Random();

	private DiceAnimation() {}

	public static void enqueue(DiceRollPayload roll) {
		QUEUE.add(roll);
	}

	public static void clear() {
		QUEUE.clear();
		current = null;
	}

	public static void tick(MinecraftClient client) {
		if (client.player == null) {
			clear();
			return;
		}
		if (current == null) {
			current = QUEUE.poll();
			age = 0;
			printed = false;
			if (current == null) return;
			random = new Random(System.nanoTime());
		}
		age++;
		if (!printed && age >= TUMBLE_TICKS) {
			printed = true;
			printResult(client, current);
		}
		if (age >= TUMBLE_TICKS + HOLD_TICKS) {
			current = null;
		}
	}

	private static void printResult(MinecraftClient client, DiceRollPayload roll) {
		int total = roll.modifier();
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < roll.results().length; i++) {
			if (i > 0) list.append(", ");
			list.append(roll.results()[i]);
			total += roll.results()[i];
		}
		String spec = (roll.results().length > 1 ? String.valueOf(roll.results().length) : "") + roll.type().label
				+ (roll.modifier() > 0 ? "+" + roll.modifier() : roll.modifier() < 0 ? String.valueOf(roll.modifier()) : "");

		Text text = Text.literal(roll.roller()).formatted(Formatting.YELLOW)
				.append(Text.literal(" rolled " + spec + ": ").formatted(Formatting.GRAY))
				.append(Text.literal("[" + list + "]").formatted(Formatting.WHITE));
		if (roll.modifier() != 0 || roll.results().length > 1) {
			text = text.copy().append(Text.literal(" = ").formatted(Formatting.GRAY))
					.append(Text.literal(String.valueOf(total)).formatted(Formatting.GOLD, Formatting.BOLD));
		}
		if (roll.type() == DiceType.D20 && roll.results().length == 1) {
			if (roll.results()[0] == 20) text = text.copy().append(Text.literal("  Critical hit!").formatted(Formatting.GREEN));
			if (roll.results()[0] == 1) text = text.copy().append(Text.literal("  Critical fail!").formatted(Formatting.RED));
		}
		client.inGameHud.getChatHud().addMessage(text);
	}

	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		DiceRollPayload roll = current;
		if (roll == null) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		TextRenderer tr = mc.textRenderer;

		float t = age + tickCounter.getTickDelta(false);
		float p = Math.min(t / TUMBLE_TICKS, 1.0f);
		float ease = 1.0f - (1.0f - p) * (1.0f - p) * (1.0f - p);

		// d% is a pair of d10: tens (00..90) and ones (0..9)
		boolean percent = roll.type() == DiceType.D100;
		int slots = roll.results().length * (percent ? 2 : 1);
		int perRow = Math.min(slots, 6);
		int rows = (slots + perRow - 1) / perRow;
		int size = 34;
		int spacing = size * 2 + 6;
		int w = ctx.getScaledWindowWidth();
		int h = ctx.getScaledWindowHeight();
		float baseY = h * 0.38f - (rows - 1) * spacing / 2f;

		Text title = Text.literal(roll.roller() + " rolls " + (roll.results().length > 1 ? roll.results().length : "")
				+ roll.type().label).formatted(Formatting.WHITE);
		ctx.drawCenteredTextWithShadow(tr, title, w / 2, (int) (baseY - size - 26), 0xFFFFFF);

		int total = roll.modifier();
		for (int slot = 0; slot < slots; slot++) {
			int die = percent ? slot / 2 : slot;
			boolean tens = percent && slot % 2 == 0;
			int result = roll.results()[die];
			int row = slot / perRow;
			int inRow = Math.min(perRow, slots - row * perRow);
			float cx = w / 2f + (slot % perRow - (inRow - 1) / 2f) * spacing;
			float cy = baseY + row * spacing;

			// each die gets its own phase so they do not tumble in lockstep
			float phase = slot * 1.7f;
			float bounce = Math.abs((float) Math.sin(p * Math.PI * 3 + phase * 0.3)) * (1.0f - p) * 70.0f;
			float rotation = (1.0f - ease) * (6.0f + phase) * (slot % 2 == 0 ? 1 : -1);
			float flip = (1.0f - ease) * (9.0f + phase);
			float squash = p >= 1.0f ? 1.0f : 0.45f + 0.55f * Math.abs((float) Math.cos(flip));

			boolean settled = p >= 0.85f;
			String label;
			DiceType shape = percent ? DiceType.D10 : roll.type();
			if (settled) {
				label = faceLabel(roll.type(), result, tens);
			} else {
				int fake = roll.type().roll(new Random((long) (t / 2) * 31 + slot * 97L));
				label = faceLabel(roll.type(), fake, tens);
			}
			drawDie(ctx, tr, shape, tens ? 0xFFB07CFF : colorOf(roll.type()), cx, cy - bounce, size,
					rotation, squash, label, p >= 1.0f);
		}

		if (p >= 1.0f) {
			for (int r : roll.results()) total += r;
			String text = roll.results().length > 1 || roll.modifier() != 0
					? "= " + total : String.valueOf(roll.results()[0]);
			ctx.getMatrices().push();
			float scale = 2.0f;
			ctx.getMatrices().translate(w / 2f, baseY + rows * spacing / 2f + 10, 0);
			ctx.getMatrices().scale(scale, scale, 1);
			ctx.drawCenteredTextWithShadow(tr, Text.literal(text).formatted(Formatting.GOLD, Formatting.BOLD), 0, 0, 0xFFFFFF);
			ctx.getMatrices().pop();
		}
	}

	private static String faceLabel(DiceType type, int value, boolean tens) {
		if (type != DiceType.D100) return String.valueOf(value);
		int tensValue = value == 100 ? 0 : (value / 10) * 10;
		int onesValue = value == 100 ? 0 : value % 10;
		return tens ? String.format("%02d", tensValue) : String.valueOf(onesValue);
	}

	private static int colorOf(DiceType type) {
		return switch (type) {
			case D4 -> 0xFF4CD964;
			case D6 -> 0xFFE0E0E0;
			case D8 -> 0xFF4DA3FF;
			case D10, D100 -> 0xFFFF7A59;
			case D12 -> 0xFFFFC857;
			case D20 -> 0xFFC83232;
		};
	}

	/** Unit-radius outline of each die as a 2D silhouette (x right, y down). */
	private static float[][] outline(DiceType type) {
		return switch (type) {
			case D4 -> regular(3, -90, 1.0f, 1.0f);
			case D6 -> regular(4, 45, 1.0f, 1.0f);
			case D8 -> regular(4, 0, 0.78f, 1.0f);
			case D10, D100 -> new float[][]{{0, -1}, {0.85f, -0.2f}, {0, 1}, {-0.85f, -0.2f}};
			case D12 -> regular(5, -90, 1.0f, 1.0f);
			case D20 -> regular(6, -90, 1.0f, 1.0f);
		};
	}

	/** A lighter inner facet to give the die some shape (triangle for d20, pentagon for d12, ...). */
	private static float[][] facet(DiceType type) {
		return switch (type) {
			case D20 -> regular(3, -90, 0.55f, 0.55f);
			case D12 -> regular(5, 90, 0.55f, 0.55f);
			case D8 -> new float[][]{{0, -1}, {0.78f, 0}, {-0.78f, 0}};
			case D10, D100 -> new float[][]{{0, -1}, {0.85f, -0.2f}, {-0.85f, -0.2f}};
			default -> null;
		};
	}

	private static float[][] regular(int n, float startDeg, float rx, float ry) {
		float[][] pts = new float[n][2];
		for (int i = 0; i < n; i++) {
			double a = Math.toRadians(startDeg + 360.0 * i / n);
			pts[i][0] = (float) Math.cos(a) * rx;
			pts[i][1] = (float) Math.sin(a) * ry;
		}
		return pts;
	}

	private static void drawDie(DrawContext ctx, TextRenderer tr, DiceType shape, int color, float cx, float cy, int size,
								float rotation, float squash, String label, boolean settled) {
		var ms = ctx.getMatrices();
		ms.push();
		ms.translate(cx, cy, 0);
		ms.multiply(RotationAxis.POSITIVE_Z.rotation(rotation));
		ms.scale(squash, 1.0f, 1.0f);

		Matrix4f m = ms.peek().getPositionMatrix();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);

		float[][] out = outline(shape);
		fan(m, out, size, 0xFF101010);
		fan(m, out, size * 0.9f, color);
		float[][] facet = facet(shape);
		if (facet != null) {
			fan(m, facet, size * 0.9f, lighten(color));
		}
		RenderSystem.enableCull();
		RenderSystem.disableBlend();

		ms.push();
		float textScale = size / 14.0f;
		ms.scale(textScale, textScale, 1.0f);
		int tw = tr.getWidth(label);
		// settle highlight: number turns gold once the die has stopped
		ctx.drawText(tr, label, -tw / 2, -4, settled ? 0xFFFFE066 : 0xFFFFFFFF, true);
		ms.pop();
		ctx.draw(); // flush the text now so the next die's polygon is layered on top of it
		ms.pop();
	}

	private static int lighten(int argb) {
		int r = Math.min(255, ((argb >> 16) & 0xFF) + 40);
		int g = Math.min(255, ((argb >> 8) & 0xFF) + 40);
		int b = Math.min(255, (argb & 0xFF) + 40);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static void fan(Matrix4f m, float[][] pts, float radius, int argb) {
		float cx = 0, cy = 0;
		for (float[] pt : pts) {
			cx += pt[0];
			cy += pt[1];
		}
		cx /= pts.length;
		cy /= pts.length;
		float a = ((argb >> 24) & 0xFF) / 255f;
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
		buf.vertex(m, cx * radius, cy * radius, 0).color(r, g, b, a);
		for (int i = 0; i <= pts.length; i++) {
			float[] pt = pts[i % pts.length];
			buf.vertex(m, pt[0] * radius, pt[1] * radius, 0).color(r, g, b, a);
		}
		BufferRenderer.drawWithGlobalProgram(buf.end());
	}
}
