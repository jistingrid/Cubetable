package dev.tacticalcombat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

/**
 * A one-line text box drawn in the mod's own style (no vanilla widget, so it lays out like everything else in the DM
 * window). The screen that owns it routes clicks, characters and keys to it.
 */
public final class TextInput {
	public String text = "";
	public final String hint;
	private final int maxLength;
	public boolean focused;

	public TextInput(String hint, int maxLength) {
		this.hint = hint;
		this.maxLength = maxLength;
	}

	public void draw(DrawContext g, TextRenderer tr, int x, int y, int w, int h, int edge, int fill) {
		g.fill(x, y, x + w, y + h, focused ? 0xFFE0B84C : edge);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
		String shown = text;
		boolean caret = focused && (System.currentTimeMillis() / 500) % 2 == 0;
		if (shown.isEmpty() && !focused) {
			g.drawText(tr, tr.trimToWidth(hint, w - 6), x + 3, y + (h - 8) / 2, 0xFF6B717C, false);
			return;
		}
		// keep the end of a long text in view
		while (tr.getWidth(shown + "_") > w - 6 && !shown.isEmpty()) shown = shown.substring(1);
		g.drawText(tr, shown + (caret ? "_" : ""), x + 3, y + (h - 8) / 2, 0xFFFFFFFF, false);
	}

	public boolean charTyped(char c) {
		if (!focused) return false;
		if (c >= ' ' && c != 127 && text.length() < maxLength) text += c;
		return true;
	}

	/** Returns true when the key was used (a focused box swallows every key so letters never reach key binds). */
	public boolean keyPressed(int keyCode, int modifiers) {
		if (!focused) return false;
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			if (!text.isEmpty()) text = text.substring(0, text.length() - 1);
		} else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
			focused = false;
		} else if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
			String clip = MinecraftClient.getInstance().keyboard.getClipboard();
			if (clip != null) {
				for (char c : clip.toCharArray()) if (c >= ' ' && c != 127 && text.length() < maxLength) text += c;
			}
		}
		return true;
	}
}
