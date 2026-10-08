package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.DiceRollPayload;
import dev.tacticalcombat.net.EndTurnPayload;
import dev.tacticalcombat.net.GridPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class TacticalCombatClient implements ClientModInitializer {
	public static final KeyBinding END_TURN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.tacticalcombat.end_turn",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_ENTER,
			"key.categories.tacticalcombat"));

	public static final KeyBinding SHEET_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.tacticalcombat.sheet",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"key.categories.tacticalcombat"));

	public static final KeyBinding DISMISS_CARD_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.tacticalcombat.dismiss_card",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_H,
			"key.categories.tacticalcombat"));

	private static final double PAN_SPEED = 0.35;
	private static final double PAN_LIMIT = 24.0;
	private static final float ROTATE_SPEED = 4.0f;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID,
				(payload, context) -> ClientCombatState.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(GridPayload.ID,
				(payload, context) -> ClientGrid.apply(payload));

		ClientPlayNetworking.registerGlobalReceiver(DiceRollPayload.ID,
				(payload, context) -> DiceAnimation.enqueue(payload));

		ClientPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.ShareCardPayload.ID,
				(payload, context) -> ShareCardHud.receive(payload));

		ServerCharacters.init();
		ClientPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.CharacterUpdatePayload.ID,
				(payload, context) -> context.client().execute(() -> ServerCharacters.receiveUpdate(payload)));
		ClientPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.CharacterRemovePayload.ID,
				(payload, context) -> context.client().execute(() -> ServerCharacters.receiveRemove(payload)));
		ClientPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.CharacterHidePayload.ID,
				(payload, context) -> context.client().execute(() -> ServerCharacters.receiveHide(payload)));
		ClientPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.RolePayload.ID,
				(payload, context) -> context.client().execute(() -> ServerCharacters.receiveRole(payload)));

		FadeModels.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientCombatState.reset();
			BlockFade.clear();
			DiceAnimation.clear();
			ShareCardHud.clear();
			ServerCharacters.clear();
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("sheet").executes(ctx -> {
					CharacterSheetScreen.requestOpen();
					return 1;
				})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("tcard")
						.then(ClientCommandManager.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg())
								.executes(ctx -> {
									ShareCardHud.recall(com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"));
									return 1;
								}))));

		HudRenderCallback.EVENT.register(CombatHud::render);
		HudRenderCallback.EVENT.register(DiceAnimation::render);
		HudRenderCallback.EVENT.register(ShareCardHud::render);
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register(
						(s, ctx, mouseX, mouseY, delta) -> ShareCardHud.drawOverScreen(ctx, delta, mouseX, mouseY)));
		WorldRenderEvents.AFTER_TRANSLUCENT.register(GridRenderer::render);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientCombatState.tick(client);
			BlockFade.tick(client);
			WalkAnimation.tick(client);
			DiceAnimation.tick(client);
			ShareCardHud.tick(client);
			CharacterSheetScreen.tick(client);
			manageScreen(client);
			if (client.currentScreen instanceof TacticalScreen) {
				pollCameraKeys(client);
			}
			while (DISMISS_CARD_KEY.wasPressed()) {
				ShareCardHud.dismiss();
			}
			while (SHEET_KEY.wasPressed()) {
				CharacterSheetScreen.requestOpen();
			}
			while (END_TURN_KEY.wasPressed()) {
				sendEndTurn();
			}
		});
	}

	public static void sendEndTurn() {
		if (ClientCombatState.isMyTurn()) {
			ClientPlayNetworking.send(EndTurnPayload.INSTANCE);
		}
	}

	/** Keeps the free-cursor screen open for exactly as long as the fight lasts. */
	private static void manageScreen(MinecraftClient client) {
		if (client.player == null || client.world == null) return;
		if (ClientCombatState.active) {
			if (client.currentScreen == null) {
				client.setScreen(new TacticalScreen());
			}
		} else if (client.currentScreen instanceof TacticalScreen) {
			client.setScreen(null);
		}
	}

	/** WASD pans the camera, the arrow keys rotate it (mouse: scroll = zoom, middle drag = rotate / tilt). */
	private static void pollCameraKeys(MinecraftClient client) {
		long window = client.getWindow().getHandle();

		float rotate = 0;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) rotate -= ROTATE_SPEED;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) rotate += ROTATE_SPEED;
		ClientCombatState.camYawTarget += rotate;

		double forward = 0;
		double side = 0;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W)) forward += 1;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S)) forward -= 1;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D)) side += 1;
		if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A)) side -= 1;
		if (forward == 0 && side == 0) return;

		double yaw = Math.toRadians(ClientCombatState.camYawTarget);
		// camera forward on the ground plane, and the camera's right-hand side
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double rx = -Math.cos(yaw);
		double rz = -Math.sin(yaw);

		Vec3d pan = ClientCombatState.pan.add(
				(fx * forward + rx * side) * PAN_SPEED, 0, (fz * forward + rz * side) * PAN_SPEED);
		double len = Math.sqrt(pan.x * pan.x + pan.z * pan.z);
		if (len > PAN_LIMIT) {
			double k = MathHelper.clamp(PAN_LIMIT / len, 0.0, 1.0);
			pan = new Vec3d(pan.x * k, 0, pan.z * k);
		}
		ClientCombatState.pan = pan;
	}
}
