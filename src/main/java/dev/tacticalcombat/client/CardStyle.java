package dev.tacticalcombat.client;

import net.minecraft.client.gui.DrawContext;

/**
 * The look of the shown-entry cards (see {@link ShareCardHud}), shared by every popup of the mod: a dark body with a
 * thin black outline, a gradient banner holding the title, small dim text under it and flat dark buttons.
 */
public final class CardStyle {
	public static final int OUTLINE = 0xFF05060A;
	public static final int BODY = 0xF2141720;
	public static final int EDGE = 0xFF3A3F4D;
	public static final int MUTED = 0xFFB7BCC8;
	public static final int DIM = 0xFF7C8394;
	public static final int WHITE = 0xFFFFFFFF;
	public static final int BUTTON = 0xFF252936;
	public static final int BUTTON_HOVER = 0xFF343A4C;

	public static final int HEADER = 22;

	/** Banner colours: gold (information), red (danger), steel (calm), grey (over). */
	public static final int[] GOLD = {0xFF6B4E12, 0xFF3A2A08};
	public static final int[] RED = {0xFF8A2A2A, 0xFF411414};
	public static final int[] STEEL = {0xFF2F5C8A, 0xFF18304A};
	public static final int[] GREY = {0xFF4A4D55, 0xFF22242A};

	private CardStyle() {}

	/** Outline, body and banner of a card; the title goes at (x + 6, y + 7). */
	public static void frame(DrawContext g, int x, int y, int w, int h, int[] banner) {
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, OUTLINE);
		g.fill(x, y, x + w, y + h, BODY);
		g.fillGradient(x, y, x + w, y + HEADER, banner[1], banner[0]);
		g.fill(x, y + HEADER - 1, x + w, y + HEADER, OUTLINE);
	}

	public static void button(DrawContext g, net.minecraft.client.font.TextRenderer tr, int x, int y, int w, int h,
							  String label, boolean hover) {
		g.fill(x, y, x + w, y + h, EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BUTTON_HOVER : BUTTON);
		g.drawCenteredTextWithShadow(tr, label, x + w / 2, y + (h - 8) / 2 + 1, WHITE);
	}

	/** Text at 3/4 size, like the card's stat and body lines. */
	public static void small(DrawContext g, net.minecraft.client.font.TextRenderer tr, String text, int x, int y, int color, int maxWidth) {
		var ms = g.getMatrices();
		ms.push();
		ms.translate(x, y, 0);
		ms.scale(0.75f, 0.75f, 1f);
		g.drawText(tr, tr.trimToWidth(text, (int) (maxWidth / 0.75f)), 0, 0, color, false);
		ms.pop();
	}
}
