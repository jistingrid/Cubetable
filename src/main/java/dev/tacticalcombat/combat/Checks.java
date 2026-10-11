package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.net.CheckPromptPayload;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.Expr;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Roll requests: something in the game asks a creature to roll a check (a saving throw, an ability check ...) and
 * acts on the result. The owner of the creature gets a prompt like the damage prompt (a player their own, the Dungeon
 * Master a creature's), picks Roll / Advantage / Disadvantage, and the server rolls it with the creature's sheet
 * (the format's {@code combat.checks}), shows the dice to everyone and calls {@code done} with the result.
 *
 * <p>Used by the {@code check} effect of rules ({@link dev.tacticalcombat.sheet.ConditionRules}); any other mechanic
 * that needs a creature to roll can call {@link #request}, in or out of combat.
 */
public final class Checks {
	/** The outcome: the total, the natural d20 and whether it reached the DC (false when there was none). */
	public record Result(int total, int natural, boolean passed) {}

	private record Pending(long id, UUID target, String targetName, String requester, String checkId, String label, int dc,
						   boolean showDc, List<UUID> owners, Consumer<Result> done) {}

	private static final Map<Long, Pending> PENDING = new HashMap<>();
	private static long nextId = 1;

	private Checks() {}

	public static void clear() {
		PENDING.clear();
	}

	/**
	 * Asks {@code target} for the check {@code checkId} (an id of the target's {@code combat.checks}; an unknown one is a
	 * plain d20). {@code dc} below 0 means no number to beat. {@code done} runs when it has been rolled.
	 */
	public static void request(MinecraftServer server, LivingEntity target, String requester, String checkId, int dc,
							   boolean showDc, Consumer<Result> done) {
		String name = Damage.nameOf(target);
		CombatRules.Check def = definition(target, checkId);
		String label = def != null ? def.label() : checkId.isEmpty() ? "check" : checkId;
		List<ServerPlayerEntity> owners = new ArrayList<>();
		if (target instanceof ServerPlayerEntity tp) {
			owners.add(tp);
		} else {
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (Roles.isDm(p.getUuid())) owners.add(p);
		}
		if (owners.isEmpty()) { // nobody to ask: roll it for them
			List<ServerPlayerEntity> all = server.getPlayerManager().getPlayerList();
			if (all.isEmpty()) return;
			roll(all.get(0), target, name, requester, checkId, label, dc, showDc, 0, done);
			return;
		}
		long id = nextId++;
		List<UUID> uuids = new ArrayList<>();
		for (ServerPlayerEntity p : owners) uuids.add(p.getUuid());
		PENDING.put(id, new Pending(id, target.getUuid(), name, requester, checkId, label, dc, showDc, uuids, done));
		CheckPromptPayload payload = new CheckPromptPayload(id, false, name, requester, label, showDc ? dc : -1);
		for (ServerPlayerEntity p : owners) ServerPlayNetworking.send(p, payload);
	}

	/** An owner pressed a button. mode: 0 roll, 1 advantage, 2 disadvantage. */
	public static void resolve(ServerPlayerEntity responder, long id, int mode) {
		Pending p = PENDING.get(id);
		if (p == null || !p.owners().contains(responder.getUuid())) return;
		PENDING.remove(id);
		MinecraftServer server = responder.getServer();
		if (server == null) return;
		CheckPromptPayload close = new CheckPromptPayload(id, true, "", "", "", -1);
		for (UUID u : p.owners()) {
			ServerPlayerEntity o = server.getPlayerManager().getPlayer(u);
			if (o != null && o != responder) ServerPlayNetworking.send(o, close);
		}
		Entity e = find(server, p.target());
		if (!(e instanceof LivingEntity target)) return;
		roll(responder, target, p.targetName(), p.requester(), p.checkId(), p.label(), p.dc(), p.showDc(), mode, p.done());
	}

	private static Entity find(MinecraftServer server, UUID id) {
		for (ServerWorld w : server.getWorlds()) {
			Entity e = w.getEntity(id);
			if (e != null) return e;
		}
		return null;
	}

	private static CombatRules.Check definition(LivingEntity target, String checkId) {
		CharacterStore.Entry entry = SheetHealth.entryOf(target);
		SheetFormat f = entry == null ? null : SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		return f == null || f.combat == null ? null : f.combat.check(checkId);
	}

	private static void roll(ServerPlayerEntity responder, LivingEntity target, String name, String requester, String checkId,
							 String label, int dc, boolean showDc, int mode, Consumer<Result> done) {
		DiceType type = DiceType.D20;
		int count = 1;
		int modifier = 0;
		CharacterStore.Entry entry = SheetHealth.entryOf(target);
		CombatRules.Check def = definition(target, checkId);
		if (entry != null && def != null) {
			SheetFormat f = SheetLibrary.FORMATS.get(entry.json.get("format").getAsString());
			try {
				SheetContext sc = new SheetContext(f, dev.tacticalcombat.sheet.CharacterData.parse(entry.json.deepCopy(), ""));
				Expr.Roll plan = sc.plan(def.roll());
				type = plan.type();
				count = plan.count();
				modifier = plan.modifier();
			} catch (RuntimeException ex) {
				// a formula that does not work: a plain d20
			}
		}
		// the target's own conditions ("I roll" rules) may add advantage, disadvantage or a bonus
		Conditions.RollEffect eff = Conditions.forRoll(target, List.of("roll"), -1);
		if (type == DiceType.D20 && count == 1 && eff.advantage() + eff.disadvantage() > 0) {
			int adv = eff.advantage() + (mode == 1 ? 1 : 0);
			int dis = eff.disadvantage() + (mode == 2 ? 1 : 0);
			mode = adv > 0 && dis == 0 ? 1 : dis > 0 && adv == 0 ? 2 : 0;
		}
		modifier += eff.bonus();
		MinecraftServer server = responder.getServer();
		if (server != null && !eff.notes().isEmpty()) {
			server.getPlayerManager().broadcast(Text.literal(name + " (" + label + "): " + String.join(", ", eff.notes())), false);
		}
		final boolean hasDc = dc >= 0;
		final int[] out = new int[2];
		int total = DiceService.rollNatural(responder, name, label + (requester.isEmpty() ? "" : " for " + requester), type, count, modifier, mode, (t, natural) -> {
			out[0] = natural;
			if (!hasDc) return DiceService.Verdict.NONE;
			boolean pass = t >= dc;
			return new DiceService.Verdict(pass ? 1 : 2, (showDc ? "DC " + dc + ": " : "") + (pass ? "success" : "failure"));
		});
		if (done != null) done.accept(new Result(total, out[0], hasDc && total >= dc));
	}
}
