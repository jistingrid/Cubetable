package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.EndTurnPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class TacticalCombatClient implements ClientModInitializer {
	public static final KeyBinding END_TURN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.tacticalcombat.end_turn",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_ENTER,
			"key.categories.tacticalcombat"));

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID,
				(payload, context) -> ClientCombatState.apply(payload));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientCombatState.reset());

		HudRenderCallback.EVENT.register(CombatHud::render);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientCombatState.tick(client);
			while (END_TURN_KEY.wasPressed()) {
				if (ClientCombatState.isMyTurn()) {
					ClientPlayNetworking.send(EndTurnPayload.INSTANCE);
				}
			}
		});
	}
}
