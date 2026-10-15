package dev.tacticalcombat.client;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.sheet.StatusIcons;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/** Draws the bundled status icons (white glyphs: tinted by the caller's badge colour behind them). */
final class StatusIconDraw {
	private static final Map<String, Identifier> TEXTURES = new HashMap<>();

	private StatusIconDraw() {}

	static Identifier texture(String id) {
		return TEXTURES.computeIfAbsent(id, k -> Identifier.of(TacticalCombatMod.MOD_ID, "textures/status/" + k + ".png"));
	}

	/** Draws icon {@code id} (an id from {@link StatusIcons}) filling a size x size square; false when there is no such icon. */
	static boolean draw(DrawContext g, String id, int x, int y, int size) {
		if (id == null || StatusIcons.get(id) == null) return false;
		g.drawTexture(texture(id), x, y, size, size, 0f, 0f, 64, 64, 64, 64);
		return true;
	}

	/** A badge of {@code size} with the icon on it, or the first letter of {@code fallback} when there is no icon. */
	static void badge(DrawContext g, TextRenderer font, String id, String fallback, int x, int y, int size) {
		g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF05060A);
		g.fill(x, y, x + size, y + size, 0xFF2A2F38);
		g.fill(x, y, x + size, y + 1, 0xFF4A515E);
		if (!draw(g, id, x + 2, y + 2, size - 4)) {
			String letter = fallback == null || fallback.isBlank() ? "?" : fallback.trim().substring(0, 1).toUpperCase();
			g.drawText(font, letter, x + (size - font.getWidth(letter)) / 2, y + (size - 8) / 2, 0xFFE0B84C, false);
		}
	}
}
