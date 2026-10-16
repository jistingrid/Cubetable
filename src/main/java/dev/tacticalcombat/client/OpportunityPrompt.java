package dev.tacticalcombat.client;

import dev.tacticalcombat.net.OpportunityPayload;
import dev.tacticalcombat.net.OpportunityPromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The attack-of-opportunity popup: a warning before walking away from engaged creatures (with the ways to disengage),
 * the offer to attack a creature that is leaving, and a Done button while that attack is made.
 */
public final class OpportunityPrompt {
	private static OpportunityPromptPayload current;

	private static final int W = 244;
	private static final int TOP = 28;

	private OpportunityPrompt() {}

	private record Btn(int x, int y, int w, int h, String label, boolean enabled, int op, String arg) {}

	public static void receive(OpportunityPromptPayload p) {
		current = p.kind() == 3 ? null : p;
	}

	public static void clear() {
		current = null;
	}

	public static boolean visible() {
		return current != null;
	}

	/** The creature (entity id) whose action bar to show while an attack of opportunity is being made, or -1. */
	public static int attackerId() {
		return current != null && current.kind() == 2 ? current.subject() : -1;
	}

	private static List<OrderedText> lines(TextRenderer font) {
		return font.wrapLines(Text.literal(current.text()), W - 12);
	}

	private static int height(TextRenderer font) {
		int rows = current.kind() == 0 ? current.options().size() : 0;
		return CardStyle.HEADER + 8 + 10 * lines(font).size() + 4 + rows * 18 + 22;
	}

	private static List<Btn> buttons(TextRenderer font, int screenW) {
		List<Btn> out = new ArrayList<>();
		int x = (screenW - W) / 2;
		int h = height(font);
		int y = TOP + CardStyle.HEADER + 8 + 10 * lines(font).size() + 4;
		if (current.kind() == 0) {
			for (OpportunityPromptPayload.Option o : current.options()) {
				String label = o.label() + (o.cost().isEmpty() ? "" : " (" + o.cost() + ")");
				out.add(new Btn(x + 6, y, W - 12, 16, font.trimToWidth(label, W - 20), o.usable(), OpportunityPayload.DISENGAGE, o.id()));
				y += 18;
			}
		}
		int by = TOP + h - 22;
		int bw = (W - 16) / 2;
		switch (current.kind()) {
			case 0 -> {
				out.add(new Btn(x + 6, by, bw, 16, "Proceed", true, OpportunityPayload.PROCEED, ""));
				out.add(new Btn(x + 10 + bw, by, bw, 16, "Stay", true, OpportunityPayload.CANCEL, ""));
			}
			case 1 -> {
				out.add(new Btn(x + 6, by, bw, 16, "Attack", true, OpportunityPayload.ATTACK, ""));
				out.add(new Btn(x + 10 + bw, by, bw, 16, "Skip", true, OpportunityPayload.DONE, ""));
			}
			default -> out.add(new Btn(x + 6, by, W - 12, 16, "Done", true, OpportunityPayload.DONE, ""));
		}
		return out;
	}

	public static void render(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW) {
		if (current == null) return;
		int x = (screenW - W) / 2;
		int h = height(font);
		CardStyle.frame(ctx, x, TOP, W, h, current.kind() == 0 ? CardStyle.RED : CardStyle.GOLD);
		String title = switch (current.kind()) {
			case 0 -> "Attack of opportunity";
			case 1 -> "Opportunity!";
			default -> "Opportunity attack";
		};
		ctx.drawText(font, title, x + 6, TOP + 7, CardStyle.WHITE, true);
		int ty = TOP + CardStyle.HEADER + 5;
		for (OrderedText line : lines(font)) {
			ctx.drawText(font, line, x + 6, ty, CardStyle.MUTED, false);
			ty += 10;
		}
		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		for (Btn b : buttons(font, screenW)) {
			boolean over = b.enabled() && mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h();
			CardStyle.button(ctx, font, b.x(), b.y(), b.w(), b.h(), b.label(), over);
			if (!b.enabled()) ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), 0xAA101218);
		}
	}

	public static boolean contains(double mx, double my, int screenW) {
		if (current == null) return false;
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		int x = (screenW - W) / 2;
		return mx >= x - 1 && mx < x + W + 1 && my >= TOP - 1 && my < TOP + height(font) + 1;
	}

	public static boolean mouseClicked(double mx, double my, int button, int screenW) {
		if (!contains(mx, my, screenW)) return false;
		if (button != 0) return true;
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		for (Btn b : buttons(font, screenW)) {
			if (!b.enabled() || mx < b.x() || mx >= b.x() + b.w() || my < b.y() || my >= b.y() + b.h()) continue;
			ClientPlayNetworking.send(new OpportunityPayload(b.op(), b.arg()));
			if (b.op() != OpportunityPayload.DISENGAGE && b.op() != OpportunityPayload.ATTACK) current = null;
			break;
		}
		return true;
	}
}
