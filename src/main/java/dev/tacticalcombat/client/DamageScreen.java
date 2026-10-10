package dev.tacticalcombat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * An invisible screen that frees the mouse so a damage prompt can be answered by someone who is not in the fight
 * (the Dungeon Master settling damage on a creature). Players in the fight already have the tactical screen.
 */
public final class DamageScreen extends Screen {
	private static long lastOpenedFor;

	public DamageScreen() {
		super(Text.empty());
	}

	/** Opens the screen when a prompt is waiting and nothing else has the mouse. */
	public static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) return;
		if (client.currentScreen instanceof DamageScreen) {
			if (!DamagePrompt.pending()) client.setScreen(null);
			return;
		}
		long id = DamagePrompt.firstId();
		if (client.currentScreen == null && DamagePrompt.visible() && id != lastOpenedFor) {
			lastOpenedFor = id;
			client.setScreen(new DamageScreen());
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// nothing: the world stays fully visible
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// the prompt itself is drawn by the HUD
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		DamagePrompt.mouseClicked(mouseX, mouseY, button, width);
		return true;
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		return DamagePrompt.charTyped(chr) || super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return DamagePrompt.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
	}
}
