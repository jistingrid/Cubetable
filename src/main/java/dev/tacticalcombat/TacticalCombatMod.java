package dev.tacticalcombat;

import com.mojang.brigadier.CommandDispatcher;
import dev.tacticalcombat.combat.Combat;
import dev.tacticalcombat.combat.CombatManager;
import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.dice.DiceSpec;
import dev.tacticalcombat.net.DiceRequestPayload;
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
		PayloadTypeRegistry.playC2S().register(DiceRequestPayload.ID, DiceRequestPayload.CODEC);
		dev.tacticalcombat.character.CharacterSync.register();
		dev.tacticalcombat.actor.ModEntities.init();
		dev.tacticalcombat.actor.ActorRegistry.register();
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.ActorActionPayload.ID, dev.tacticalcombat.net.ActorActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.ActorActionPayload.ID, (payload, context) ->
				context.server().execute(() -> dev.tacticalcombat.actor.ActorService.handle(context.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(DiceRequestPayload.ID, (payload, context) ->
				context.server().execute(() -> dev.tacticalcombat.combat.Strikes.roll(context.player(), payload.label(),
						payload.type(), payload.count(), payload.modifier(), payload.mode(), payload.kind(), payload.cost())));
		PayloadTypeRegistry.playS2C().register(dev.tacticalcombat.net.DmStatePayload.ID, dev.tacticalcombat.net.DmStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.DmActionPayload.ID, dev.tacticalcombat.net.DmActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.DmActionPayload.ID, (payload, context) ->
				context.server().execute(() -> dev.tacticalcombat.combat.DmTools.action(context.player(), payload.op(), payload.value())));
		ServerTickEvents.END_SERVER_TICK.register(dev.tacticalcombat.combat.DmTools::tick);
		PayloadTypeRegistry.playS2C().register(dev.tacticalcombat.net.PossessPayload.ID, dev.tacticalcombat.net.PossessPayload.CODEC);
		dev.tacticalcombat.actor.Possession.register();
		PayloadTypeRegistry.playS2C().register(dev.tacticalcombat.net.PausePayload.ID, dev.tacticalcombat.net.PausePayload.CODEC);
		dev.tacticalcombat.combat.TimePause.register();
		PayloadTypeRegistry.playS2C().register(dev.tacticalcombat.net.DownedPayload.ID, dev.tacticalcombat.net.DownedPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.DownedActionPayload.ID, dev.tacticalcombat.net.DownedActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.DownedActionPayload.ID, (payload, context) ->
				context.server().execute(() -> dev.tacticalcombat.combat.Downed.handle(context.player(), payload)));
		dev.tacticalcombat.combat.Downed.register();
		ServerTickEvents.END_SERVER_TICK.register(dev.tacticalcombat.combat.Downed::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.tacticalcombat.combat.TimePause::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.tacticalcombat.actor.Possession::tick);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.TargetPayload.ID, dev.tacticalcombat.net.TargetPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(dev.tacticalcombat.net.DamagePromptPayload.ID, dev.tacticalcombat.net.DamagePromptPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.DamageChoicePayload.ID, dev.tacticalcombat.net.DamageChoicePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.TargetPayload.ID, (payload, context) ->
				context.server().execute(() -> CombatManager.requestTarget(context.player(), payload.entityId())));
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.DamageChoicePayload.ID, (payload, context) ->
				context.server().execute(() -> dev.tacticalcombat.combat.Damage.resolve(context.player(), payload.id(),
						payload.mode(), payload.amount())));
		PayloadTypeRegistry.playS2C().register(dev.tacticalcombat.net.ShareCardPayload.ID, dev.tacticalcombat.net.ShareCardPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.ShareCardRequestPayload.ID, dev.tacticalcombat.net.ShareCardRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.ShareCardRequestPayload.ID, (payload, context) ->
				context.server().execute(() -> dev.tacticalcombat.card.CardService.share(context.player(), payload.card())));
		PayloadTypeRegistry.playC2S().register(EndTurnPayload.ID, EndTurnPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(MoveRequestPayload.ID, MoveRequestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(AttackRequestPayload.ID, AttackRequestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.BuyMovePayload.ID, dev.tacticalcombat.net.BuyMovePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.tacticalcombat.net.EncounterActionPayload.ID, dev.tacticalcombat.net.EncounterActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.EncounterActionPayload.ID,
				(payload, context) -> context.server().execute(() ->
						CombatManager.requestEncounter(context.player(), payload.op(), payload.entityId(), payload.value())));
		ServerPlayNetworking.registerGlobalReceiver(dev.tacticalcombat.net.BuyMovePayload.ID,
				(payload, context) -> CombatManager.requestBuyMove(context.player(), payload.id()));
		ServerPlayNetworking.registerGlobalReceiver(EndTurnPayload.ID,
				(payload, context) -> CombatManager.requestEndTurn(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MoveRequestPayload.ID,
				(payload, context) -> CombatManager.requestMove(context.player(), payload.target()));
		ServerPlayNetworking.registerGlobalReceiver(AttackRequestPayload.ID,
				(payload, context) -> CombatManager.requestAttack(context.player(), payload.entityId()));

		// Combat loop
		ServerTickEvents.END_SERVER_TICK.register(CombatManager::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CombatManager.clear());
		// the server reads the game packs too (movement, resources ... come from the pack's "combat" block)
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			if (dev.tacticalcombat.sheet.SheetLibrary.FORMATS.isEmpty()) dev.tacticalcombat.sheet.SheetLibrary.reloadPacks();
		});

		// Rules: attacks cost the action, nobody acts out of turn
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(CombatManager::allowDamage);
		// ... and a player with sheet hit points loses those instead of Minecraft health
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(dev.tacticalcombat.combat.SheetHealth::allowDamage);
		ServerTickEvents.END_SERVER_TICK.register(dev.tacticalcombat.combat.SheetHealth::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			dev.tacticalcombat.combat.SheetHealth.clear();
			dev.tacticalcombat.combat.Damage.clear();
		});

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
		dev.tacticalcombat.actor.ActorCommands.register(dispatcher);
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
				.then(CommandManager.literal("reload")
						.requires(src -> src.hasPermissionLevel(2))
						.executes(ctx -> {
							dev.tacticalcombat.sheet.SheetLibrary.reloadPacks();
							ctx.getSource().sendFeedback(() -> Text.literal("Reloaded " + dev.tacticalcombat.sheet.SheetLibrary.FORMATS.size()
									+ " sheet formats (" + dev.tacticalcombat.sheet.SheetLibrary.PROBLEMS.size() + " problems)."), true);
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
									DiceService.roll(player, "", spec.type(), spec.count(), spec.modifier(), 0);
									return 1;
								})))
				.then(CommandManager.literal("dm")
						.requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.literal("add")
								.then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
										.executes(ctx -> {
											ServerPlayerEntity target = net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player");
											boolean changed = dev.tacticalcombat.character.Roles.add(target.getUuid());
											dev.tacticalcombat.character.CharacterSync.resync(target);
											ctx.getSource().sendFeedback(() -> Text.literal(target.getGameProfile().getName()
													+ (changed ? " is now a Dungeon Master." : " already is a Dungeon Master.")), true);
											return 1;
										})))
						.then(CommandManager.literal("remove")
								.then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
										.executes(ctx -> {
											ServerPlayerEntity target = net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player");
											boolean changed = dev.tacticalcombat.character.Roles.remove(target.getUuid());
											dev.tacticalcombat.character.CharacterSync.resync(target);
											ctx.getSource().sendFeedback(() -> Text.literal(target.getGameProfile().getName()
													+ (changed ? " is no longer a Dungeon Master." : " was not a Dungeon Master.")), true);
											return 1;
										})))
						.then(CommandManager.literal("list")
								.executes(ctx -> {
									java.util.List<String> names = new java.util.ArrayList<>();
									for (java.util.UUID id : dev.tacticalcombat.character.Roles.all()) {
										ServerPlayerEntity online = ctx.getSource().getServer().getPlayerManager().getPlayer(id);
										names.add(online != null ? online.getGameProfile().getName() : id.toString());
									}
									ctx.getSource().sendFeedback(() -> Text.literal(names.isEmpty() ? "No Dungeon Masters yet. Use /tbc dm add <player>."
											: "Dungeon Masters: " + String.join(", ", names)), false);
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
