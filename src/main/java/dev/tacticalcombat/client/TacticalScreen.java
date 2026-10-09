package dev.tacticalcombat.client;

import dev.tacticalcombat.grid.Grid;
import dev.tacticalcombat.net.AttackRequestPayload;
import dev.tacticalcombat.net.MoveRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Invisible screen that is open for the whole fight. Its only job is to give the player a free mouse
 * cursor (instead of mouse-look) so squares and enemies can be clicked, and to route the few vanilla
 * keys that still make sense (pause, inventory, chat, hotbar) while it is open.
 */
public final class TacticalScreen extends Screen {
	public TacticalScreen() {
		super(Text.empty());
	}

	@Override
	public boolean shouldPause() {
		return false; // the fight keeps running on an integrated server
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// no dimming / blur: the battlefield must stay fully visible
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		boolean onPanel = TrackerPanel.contains(mouseX, mouseY, width, height)
				|| ActionBar.contains(mouseX, mouseY, width, height);
		if (onPanel) { // the pointer is on the DM's tracker, not on the battlefield
			ClientGrid.hover = -1;
			ClientGrid.hoverEntity = -1;
		} else {
			MousePicker.update(mc, mouseX, mouseY);
		}

		String panelTip = TrackerPanel.render(ctx, mc.textRenderer, mouseX, mouseY, width, height);
		Text tip = onPanel ? (panelTip == null ? null : Text.literal(panelTip)) : tooltip(mc);
		if (tip != null) {
			ctx.drawTooltip(mc.textRenderer, tip, mouseX, mouseY);
		}
	}

	private static Text tooltip(MinecraftClient mc) {
		if (ClientGrid.hoverEntity >= 0 && mc.world != null && mc.player != null) {
			Entity target = mc.world.getEntityById(ClientGrid.hoverEntity);
			if (target == null) return null;
			boolean inReach = Grid.distanceToBox(mc.player.getEyePos(), target.getBoundingBox()) <= Grid.ATTACK_REACH;
			if (ClientCombatState.actionUsed()) {
				return Text.translatable("tacticalcombat.tip.no_action");
			}
			return inReach
					? Text.translatable("tacticalcombat.tip.attack", target.getName())
					: Text.translatable("tacticalcombat.tip.too_far", target.getName());
		}
		if (ClientGrid.hover >= 0 && ClientGrid.hover < ClientGrid.cells.size()) {
			return Text.translatable("tacticalcombat.tip.move", ClientCombatState.distance(ClientGrid.cells.get(ClientGrid.hover).cost()));
		}
		return null;
	}

	private static MinecraftClient mc() {
		return MinecraftClient.getInstance();
	}

	// ---------------------------------------------------------------- mouse

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (TrackerPanel.mouseClicked(mouseX, mouseY, button, width, height)) return true;
		if (ActionBar.mouseClicked(mouseX, mouseY, button, width, height)) return true;
		if (button != 0 || !ClientCombatState.isMyTurn()) return true;

		if (ClientGrid.hoverEntity >= 0) {
			ClientPlayNetworking.send(new AttackRequestPayload(ClientGrid.hoverEntity));
		} else if (ClientGrid.hover >= 0 && ClientGrid.hover < ClientGrid.cells.size()) {
			BlockPos target = BlockPos.fromLong(ClientGrid.cells.get(ClientGrid.hover).pos());
			ClientPlayNetworking.send(new MoveRequestPayload(target));
			ClientGrid.hover = -1; // the server clears the highlights while the walk is in progress
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (button == 0 && TrackerPanel.mouseDragged(deltaX, deltaY)) return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			ClientCombatState.camYawTarget += (float) deltaX * 0.4f;
			ClientCombatState.camPitch = MathHelper.clamp(ClientCombatState.camPitch + (float) deltaY * 0.25f, 25f, 80f);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (TrackerPanel.mouseReleased()) return true;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public void removed() {
		TrackerPanel.clearHighlight();
		super.removed();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (TrackerPanel.mouseScrolled(mouseX, mouseY, verticalAmount, width, height)) return true;
		ClientCombatState.zoomTarget = MathHelper.clamp(ClientCombatState.zoomTarget - verticalAmount * 2.0, 8.0, 40.0);
		return true;
	}

	// ---------------------------------------------------------------- keys

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		MinecraftClient mc = MinecraftClient.getInstance();

		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			mc.setScreen(new GameMenuScreen(true));
			return true;
		}
		if (ShareCardHud.visible() && TacticalCombatClient.DISMISS_CARD_KEY.matchesKey(keyCode, scanCode)) {
			ShareCardHud.dismiss();
			return true;
		}
		if (TacticalCombatClient.SHEET_KEY.matchesKey(keyCode, scanCode)) {
			CharacterSheetScreen.requestOpen();
			return true;
		}
		if (TacticalCombatClient.ENCOUNTER_KEY.matchesKey(keyCode, scanCode)) {
			EncounterScreen.requestOpen();
			return true;
		}
		if (TacticalCombatClient.END_TURN_KEY.matchesKey(keyCode, scanCode)) {
			TacticalCombatClient.sendEndTurn();
			return true;
		}
		if (ActionBar.keyPressed(keyCode)) return true;
		if (mc.player != null) {
			if (mc.options.inventoryKey.matchesKey(keyCode, scanCode)) {
				mc.setScreen(new InventoryScreen(mc.player));
				return true;
			}
			for (int i = 0; i < mc.options.hotbarKeys.length; i++) {
				if (mc.options.hotbarKeys[i].matchesKey(keyCode, scanCode)) {
					mc.player.getInventory().selectedSlot = i;
					return true;
				}
			}
		}
		if (mc.options.chatKey.matchesKey(keyCode, scanCode)) {
			mc.setScreen(new ChatScreen(""));
			return true;
		}
		if (mc.options.commandKey.matchesKey(keyCode, scanCode)) {
			mc.setScreen(new ChatScreen("/"));
			return true;
		}
		return false;
	}
}
