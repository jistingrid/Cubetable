package dev.tacticalcombat.actor;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.combat.DmTools;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Function;

/**
 * {@code /actor ...}: the same things as the Actors window, typed. Names with spaces go in quotes
 * ({@code /actor spawn "Wolf 2"}); the window and the commands can be mixed freely.
 */
public final class ActorCommands {
	private ActorCommands() {}

	private static boolean allowed(ServerCommandSource src) {
		if (src.hasPermissionLevel(2)) return true;
		ServerPlayerEntity p = src.getPlayer();
		return p != null && Roles.isDm(p.getUuid());
	}

	private static final SuggestionProvider<ServerCommandSource> ACTORS = (ctx, builder) -> {
		for (ActorRecord r : ActorRegistry.all()) {
			String n = r.name.contains(" ") ? "\"" + r.name + "\"" : r.name;
			if (n.toLowerCase().startsWith(builder.getRemainingLowerCase())) builder.suggest(n);
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<ServerCommandSource> SHEETS = (ctx, builder) -> {
		for (CharacterStore.Entry e : CharacterStore.all()) {
			String name = ActorRegistry.nameOfSheet(e);
			if (name.isEmpty()) continue;
			String n = name.contains(" ") ? "\"" + name + "\"" : name;
			if (n.toLowerCase().startsWith(builder.getRemainingLowerCase())) builder.suggest(n);
		}
		return builder.buildFuture();
	};

	private static LiteralArgumentBuilder<ServerCommandSource> lit(String s) {
		return CommandManager.literal(s);
	}

	private static RequiredArgumentBuilder<ServerCommandSource, String> actorArg() {
		return CommandManager.argument("actor", StringArgumentType.string()).suggests(ACTORS);
	}

	private static String actorId(CommandContext<ServerCommandSource> ctx) {
		ActorRecord r = ActorRegistry.find(StringArgumentType.getString(ctx, "actor"));
		return r == null ? StringArgumentType.getString(ctx, "actor") : r.id;
	}

	/** Runs an operation that answers null (done) or a sentence (why not) and reports it. */
	private static int run(CommandContext<ServerCommandSource> ctx, Function<CommandContext<ServerCommandSource>, String> op, String done) {
		String error = op.apply(ctx);
		if (error != null) {
			ctx.getSource().sendError(Text.literal(error));
			return 0;
		}
		if (done != null) ctx.getSource().sendFeedback(() -> Text.literal(done), false);
		if (ctx.getSource().getServer() != null) DmTools.broadcast(ctx.getSource().getServer());
		return 1;
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		LiteralArgumentBuilder<ServerCommandSource> root = lit("actor").requires(ActorCommands::allowed);

		root.then(lit("list").executes(ctx -> {
			List<ActorRecord> all = ActorRegistry.all();
			if (all.isEmpty()) {
				ctx.getSource().sendFeedback(() -> Text.literal("No Actors yet. /actor create <name> <sheet>"), false);
				return 1;
			}
			for (ActorRecord r : all) {
				boolean placed = ActorRegistry.bodyOf(ctx.getSource().getServer(), r) != null;
				String line = r.name + " - " + r.kind + " " + r.value + ", "
						+ (r.disposition == 0 ? "hostile" : r.disposition == 1 ? "neutral" : "friendly")
						+ (r.dmControl ? ", DM controlled" : "") + (r.ownsSheet ? ", own sheet" : ", shared sheet")
						+ (placed ? ", placed" : ", not placed")
						+ (r.folder.isEmpty() ? "" : ", folder " + r.folder)
						+ (r.tags.isEmpty() ? "" : ", tags " + String.join("/", r.tags));
				ctx.getSource().sendFeedback(() -> Text.literal(line), false);
			}
			return all.size();
		}));

		// /actor create <name> <sheet> [copy|linked]
		Function<Boolean, com.mojang.brigadier.Command<ServerCommandSource>> create = linked -> ctx -> run(ctx, c -> {
			CharacterStore.Entry sheet = ActorRegistry.sheetNamed(StringArgumentType.getString(c, "sheet"));
			if (sheet == null) return "No sheet called " + StringArgumentType.getString(c, "sheet") + ".";
			try {
				return ActorService.create(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name"), sheet.id,
						linked, "mob", "minecraft:villager", 0);
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				return "Only a player can create Actors.";
			}
		}, "Created. Give it a look with /actor model, then place it with /actor spawn.");
		root.then(lit("create").then(CommandManager.argument("name", StringArgumentType.string())
				.then(CommandManager.argument("sheet", StringArgumentType.string()).suggests(SHEETS)
						.executes(create.apply(false))
						.then(lit("copy").executes(create.apply(false)))
						.then(lit("linked").executes(create.apply(true))))));

		root.then(lit("spawn").then(actorArg().executes(ctx -> run(ctx, c -> {
			try {
				return ActorService.spawn(c.getSource().getPlayerOrThrow(), actorId(c));
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				return "Only a player can place Actors.";
			}
		}, "Placed where you are looking."))));
		root.then(lit("recall").then(actorArg().executes(ctx -> run(ctx,
				c -> ActorService.recall(c.getSource().getServer(), actorId(c)), "Taken out of the world."))));
		root.then(lit("remove").then(actorArg().executes(ctx -> run(ctx,
				c -> ActorService.delete(c.getSource().getServer(), actorId(c)), "Removed."))));
		root.then(lit("duplicate").then(actorArg().executes(ctx -> run(ctx, c -> {
			try {
				return ActorService.duplicate(c.getSource().getPlayerOrThrow(), actorId(c));
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				return "Only a player can do that.";
			}
		}, "Copied."))));
		root.then(lit("fight").then(actorArg().executes(ctx -> run(ctx,
				c -> ActorService.addToFight(c.getSource().getServer(), actorId(c)), "Added to the fight."))));

		// /actor model <actor> <mob|skin|item|block> <value>
		for (String kind : new String[]{"mob", "skin", "item", "block"}) {
			root.then(lit("model").then(actorArg().then(lit(kind).then(
					CommandManager.argument("value", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
							c -> ActorService.setModel(c.getSource().getServer(), actorId(c), kind,
									StringArgumentType.getString(c, "value")), "Model changed."))))));
		}

		root.then(lit("control").then(actorArg()
				.then(lit("dm").executes(ctx -> run(ctx, c -> ActorService.setControl(c.getSource().getServer(), actorId(c), true),
						"You walk and act for it in fights.")))
				.then(lit("auto").executes(ctx -> run(ctx, c -> ActorService.setControl(c.getSource().getServer(), actorId(c), false),
						"It takes its own turns (unless Auto movement is off in the DM tools).")))));

		String[] sides = {"hostile", "neutral", "friendly"};
		for (int i = 0; i < sides.length; i++) {
			final int d = i;
			root.then(lit(sides[i]).then(actorArg().executes(ctx -> run(ctx,
					c -> ActorService.setDisposition(c.getSource().getServer(), actorId(c), d), "Now " + sides[d] + "."))));
		}

		// /actor sheet <actor> <sheet> [copy|linked]
		Function<Boolean, com.mojang.brigadier.Command<ServerCommandSource>> sheet = linked -> ctx -> run(ctx, c -> {
			CharacterStore.Entry s = ActorRegistry.sheetNamed(StringArgumentType.getString(c, "sheet"));
			if (s == null) return "No sheet called " + StringArgumentType.getString(c, "sheet") + ".";
			try {
				return ActorService.setSheet(c.getSource().getPlayerOrThrow(), actorId(c), s.id, linked);
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				return "Only a player can do that.";
			}
		}, "Sheet changed.");
		root.then(lit("sheet").then(actorArg().then(CommandManager.argument("sheet", StringArgumentType.string()).suggests(SHEETS)
				.executes(sheet.apply(false))
				.then(lit("copy").executes(sheet.apply(false)))
				.then(lit("linked").executes(sheet.apply(true))))));

		root.then(lit("rename").then(actorArg().then(CommandManager.argument("to", StringArgumentType.string()).executes(ctx -> run(ctx,
				c -> ActorService.rename(c.getSource().getServer(), actorId(c), StringArgumentType.getString(c, "to")), "Renamed.")))));

		// /actor folder <actor> <name...>   and   /actor unfile <actor>   and   /actor tags <actor> <a,b,c>
		root.then(lit("folder").then(actorArg().then(CommandManager.argument("name", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
				c -> ActorService.setFolder(actorId(c), StringArgumentType.getString(c, "name")), "Filed.")))));
		root.then(lit("unfile").then(actorArg().executes(ctx -> run(ctx, c -> ActorService.setFolder(actorId(c), ""), "Out of its folder."))));
		root.then(lit("tags").then(actorArg().then(CommandManager.argument("tags", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
				c -> ActorService.setTags(actorId(c), StringArgumentType.getString(c, "tags")), "Tags set.")))));

		dispatcher.register(root);
	}
}
