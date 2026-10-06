package dev.tacticalcombat;

import com.mojang.brigadier.CommandDispatcher;
import dev.tacticalcombat.combat.Combat;
import dev.tacticalcombat.combat.CombatManager;
import dev.tacticalcombat.dice.DiceSpec;
import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.DiceRollPayload;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.tacticalcombat.net.AttackRequestPayload;
import dev.tacticalcombat.net.EndTurnPayload;
import dev.tacticalcombat.net.GridPayload;
import dev.tacticalcombat.net.MoveRequestPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TacticalCombatMod implements ModInitializer {
	public static final String MOD_ID = "tacticalcombat";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Networking
		PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(GridPayload.ID, GridPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DiceRollPayload.ID, DiceRollPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(EndTurnPayload.ID, EndTurnPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(MoveRequestPayload.ID, MoveRequestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AttackRequestPayload.ID, AttackRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(EndTurnPayload.ID,
				(payload, context) -> CombatManager.requestEndTurn(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MoveRequestPayload.ID,
				(payload, context) -> CombatManager.requestMove(context.player(), payload.target()));
		ServerPlayNetworking.registerGlobalReceiver(AttackRequestPayload.ID,
				(payload, context) -> CombatManager.requestAttack(context.player(), payload.entityId()));

		// Combat loop
		ServerTickEvents.END_SERVER_TICK.register(CombatManager::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CombatManager.clear());

		// Rules: attacks cost the action, nobody acts out of turn
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(CombatManager::allowDamage);

		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClient || CombatManager.canActNow(player)) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}
			CombatManager.notifyNotYourTurn(player);
			return TypedActionResult.fail(player.getStackInHand(hand));
		});
		UseBlockCallback.EVENT.register((player, world, hand, hit) ->
				world.isClient || CombatManager.canActNow(player) ? ActionResult.PASS : ActionResult.FAIL);
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
				world.isClient || CombatManager.canActNow(player) ? ActionResult.PASS : ActionResult.FAIL);

		// Commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

		LOGGER.info("Tactical Combat loaded");
	}

	private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("tbc")
				.then(CommandManager.literal("start")
						.requires(src -> src.hasPermissionLevel(2))
						.executes(ctx -> {
							ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
							Combat combat = CombatManager.startAround(player.getServerWorld(), player, false);
							if (combat == null) {
								ctx.getSource().sendError(Text.translatable("tacticalcombat.cmd.no_enemies"));
								return 0;
							}
							ctx.getSource().sendFeedback(() -> Text.translatable("tacticalcombat.cmd.started"), false);
							return 1;
						}))
				.then(CommandManager.literal("end")
						.requires(src -> src.hasPermissionLevel(2))
						.executes(ctx -> {
							ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
							Combat combat = CombatManager.get(player);
							if (combat == null) {
								ctx.getSource().sendError(Text.translatable("tacticalcombat.cmd.not_in_combat"));
								return 0;
							}
							CombatManager.endCombat(combat);
							ctx.getSource().sendFeedback(() -> Text.translatable("tacticalcombat.cmd.ended"), false);
							return 1;
						}))
				.then(CommandManager.literal("roll")
						.then(CommandManager.argument("dice", StringArgumentType.word())
								.executes(ctx -> {
									ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
									String text = StringArgumentType.getString(ctx, "dice");
									DiceSpec spec = DiceSpec.parse(text);
									if (spec == null) {
										ctx.getSource().sendError(Text.literal("Unknown dice '" + text
												+ "'. Try d4, d6, d8, d10, d%, d12, d20, 2d6 or d20+5 (max "
												+ DiceSpec.MAX_COUNT + " dice)."));
										return 0;
									}
									// the server decides the numbers; every player sees the same animation
									DiceRollPayload payload = new DiceRollPayload(player.getGameProfile().getName(),
											spec.type(), spec.modifier(), spec.roll(new java.util.Random()));
									for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
										ServerPlayNetworking.send(p, payload);
									}
									return 1;
								})))
				.then(CommandManager.literal("endturn")
						.executes(ctx -> {
							ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
							if (!CombatManager.isInCombat(player)) {
								ctx.getSource().sendError(Text.translatable("tacticalcombat.cmd.not_in_combat"));
								return 0;
							}
							CombatManager.requestEndTurn(player);
							return 1;
						})));
	}
}
