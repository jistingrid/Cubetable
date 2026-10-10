package dev.tacticalcombat.combat;

import dev.tacticalcombat.actor.ActorRecord;
import dev.tacticalcombat.actor.ActorRegistry;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.net.DownedActionPayload;
import dev.tacticalcombat.net.DownedPayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What happens when a character's health bar reaches 0, as the game's pack describes it ({@code combat.downed}).
 *
 * <p>A player character (or any sheet when the pack says {@code "applies": "all"}) is <b>downed</b>: its model lies
 * on the ground, it cannot walk, and its player gets a card. If the pack has a death save the card asks for it (in a
 * fight once per turn, on its own turn); enough successes make it <b>stable</b>, enough failures <b>dead</b>. Damage
 * while down counts as failures, healing above 0 gets it up. A creature's sheet ({@code kind "npc"}) is simply dead at 0.
 * Without a death save a downed character stays down until a Dungeon Master (or healing) gets it up.
 * Dungeon Masters see every card and can stabilize, kill or revive.
 */
public final class Downed {
	public static final int DOWNED = 0;
	public static final int STABLE = 1;
	public static final int DEAD = 2;
	public static final int UP = 3;

	private static final class State {
		int status = DOWNED;
		int successes;
		int failures;
		String name = "";
		CombatRules.Downed rule;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private Downed() {}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (Map.Entry<UUID, State> e : STATES.entrySet()) {
				Entity body = find(server, e.getKey());
				if (body instanceof LivingEntity le) sendTo(handler.player, le, e.getValue(), true);
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> STATES.clear());
	}

	// ---------------------------------------------------------------- queries

	/** Down, stable or dead: lying on the ground. */
	public static boolean isDown(Entity e) {
		return e != null && STATES.containsKey(e.getUuid());
	}

	public static boolean any() {
		return !STATES.isEmpty();
	}

	/** Death saves are still being rolled. */
	public static boolean isDying(Entity e) {
		State s = e == null ? null : STATES.get(e.getUuid());
		return s != null && s.status == DOWNED;
	}

	private static Entity find(MinecraftServer server, UUID id) {
		for (ServerWorld w : server.getWorlds()) {
			Entity e = w.getEntity(id);
			if (e != null) return e;
		}
		return null;
	}

	private static CharacterStore.Entry entryOf(LivingEntity e) {
		return SheetHealth.entryOf(e);
	}

	private static CombatRules.Downed ruleOf(CharacterStore.Entry entry) {
		if (entry == null) return null;
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		return f == null || f.combat == null ? null : f.combat.downed;
	}

	private static String nameOf(LivingEntity e) {
		return Damage.nameOf(e);
	}

	// ---------------------------------------------------------------- state changes

	/** Every half second: look for health bars that reached 0 (whatever lowered them) or got filled again. */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 10 != 0) return;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (!p.isSpectator()) check(server, p);
		}
		for (ActorRecord r : ActorRegistry.all()) {
			LivingEntity body = ActorRegistry.bodyOf(server, r);
			if (body != null) check(server, body);
		}
	}

	/** Compares one creature's health with 0 and starts or ends its downed state. */
	public static void check(MinecraftServer server, LivingEntity e) {
		SheetHealth.Hp hp = SheetHealth.of(e);
		CharacterStore.Entry entry = entryOf(e);
		CombatRules.Downed rule = ruleOf(entry);
		State s = STATES.get(e.getUuid());
		if (hp == null || rule == null || hp.max() <= 0) return;
		boolean zero = hp.now() <= 0;
		if (zero && s == null) down(server, e, entry, rule);
		else if (!zero && s != null) getUp(server, e, s);
	}

	private static void down(MinecraftServer server, LivingEntity e, CharacterStore.Entry entry, CombatRules.Downed rule) {
		State s = new State();
		s.name = nameOf(e);
		s.rule = rule;
		boolean creature = !(e instanceof ServerPlayerEntity) && entry.json.has("kind")
				&& entry.json.get("kind").getAsString().equalsIgnoreCase("npc");
		if (creature && !rule.all()) s.status = DEAD;
		STATES.put(e.getUuid(), s);
		String what = s.status == DEAD ? "is dead" : "is " + rule.label().toLowerCase(java.util.Locale.ROOT);
		server.getPlayerManager().broadcast(Text.literal(s.name + " " + what + "!"), false);
		send(server, e, s, s.status != DEAD || e instanceof ServerPlayerEntity, "");
		Combat c = CombatManager.get(e);
		if (c != null) c.sync();
	}

	private static void getUp(MinecraftServer server, LivingEntity e, State s) {
		STATES.remove(e.getUuid());
		s.status = UP;
		server.getPlayerManager().broadcast(Text.literal(s.name + " is back on their feet."), false);
		send(server, e, s, false, "");
		Combat c = CombatManager.get(e);
		if (c != null) c.sync();
	}

	/** Call right after damage was dealt to {@code e}; {@code wasDown} is {@link #isDown} from before the damage. */
	public static void afterDamage(MinecraftServer server, LivingEntity e, int amount, boolean wasDown) {
		State s = STATES.get(e.getUuid());
		if (wasDown && s != null && amount > 0 && s.status != DEAD && s.rule != null && s.rule.save() != null) {
			if (s.status == STABLE) {
				s.status = DOWNED;
				s.successes = 0;
			}
			s.failures += s.rule.save().damageFails();
			if (s.failures >= s.rule.save().failures()) s.status = DEAD;
			announce(server, s, "took damage while down");
			send(server, e, s, true, "A hit while down counts as a failure.");
			return;
		}
		check(server, e);
	}

	private static void announce(MinecraftServer server, State s, String why) {
		String text = s.name + " " + why + ": ";
		if (s.status == DEAD) text += "dead.";
		else if (s.status == STABLE) text += "stable.";
		else if (s.rule != null && s.rule.save() != null) {
			text += s.successes + "/" + s.rule.save().successes() + " successes, " + s.failures + "/" + s.rule.save().failures() + " failures.";
		}
		server.getPlayerManager().broadcast(Text.literal(text), false);
	}

	/**
	 * A new turn of a downed combatant begins. Returns true when the turn is skipped (stable or dead); a combatant that
	 * is still dying gets nothing to spend and its card back, so it can roll.
	 */
	public static boolean onTurn(MinecraftServer server, LivingEntity e, Combatant c) {
		State s = STATES.get(e.getUuid());
		if (s == null) return false;
		if (s.status != DOWNED) return true;
		c.left.replaceAll((k, v) -> 0);
		c.moveBudget = 0;
		send(server, e, s, true, "Your turn: roll the death save.");
		return false;
	}

	// ---------------------------------------------------------------- the card

	private static void send(MinecraftServer server, LivingEntity e, State s, boolean prompt, String note) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			sendTo(p, e, s, prompt, note);
		}
	}

	private static void sendTo(ServerPlayerEntity p, LivingEntity e, State s, boolean prompt) {
		sendTo(p, e, s, prompt, "");
	}

	private static void sendTo(ServerPlayerEntity p, LivingEntity e, State s, boolean prompt, String note) {
		boolean owner = p == e;
		boolean dm = Roles.isDm(p.getUuid());
		CombatRules.Save save = s.rule == null ? null : s.rule.save();
		boolean canRoll = s.status == DOWNED && save != null && (owner || (dm && !(e instanceof ServerPlayerEntity)));
		boolean shown = prompt && (owner || dm);
		ServerPlayNetworking.send(p, new DownedPayload(e.getId(), s.name, s.status, s.successes, s.failures,
				save == null ? 0 : save.successes(), save == null ? 0 : save.failures(), shown, canRoll, dm,
				s.rule == null ? "Downed" : s.rule.label(), save == null ? "" : save.label(), note));
	}

	// ---------------------------------------------------------------- the death save

	public static void handle(ServerPlayerEntity player, DownedActionPayload p) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		Entity found = null;
		for (ServerWorld w : server.getWorlds()) {
			found = w.getEntityById(p.entityId());
			if (found != null) break;
		}
		if (!(found instanceof LivingEntity e)) return;
		State s = STATES.get(e.getUuid());
		if (s == null) return;
		boolean dm = Roles.isDm(player.getUuid());
		boolean owner = player == e;

		switch (p.op()) {
			case 0 -> {
				if (!(owner || (dm && !(e instanceof ServerPlayerEntity)))) return;
				roll(server, player, e, s);
			}
			case 1 -> {
				if (!dm || s.status == UP) return;
				s.status = STABLE;
				announce(server, s, "was stabilized");
				send(server, e, s, false, "");
			}
			case 2 -> {
				if (!dm) return;
				s.status = DEAD;
				announce(server, s, "was killed");
				send(server, e, s, false, "");
			}
			case 3 -> {
				if (!dm) return;
				String vital = SheetHealth.vitalId(e);
				if (vital != null) SheetHealth.change(server, e, vital, 1);
				check(server, e);
			}
			default -> {
			}
		}
		Combat c = CombatManager.get(e);
		if (c != null) c.sync();
	}

	private static DiceType diceOf(String text) {
		String t = text.toLowerCase(java.util.Locale.ROOT).trim();
		int d = t.indexOf('d');
		DiceType type = d < 0 ? null : DiceType.fromSides(t.substring(d + 1));
		return type == null ? DiceType.D20 : type;
	}

	/** 0 failure, 1 success, 2 natural success (gets up), 3 natural failure. */
	private static int outcome(CombatRules.Save save, int natural, int total) {
		if (save.natSuccess() > 0 && natural == save.natSuccess()) return 2;
		if (save.natFailure() > 0 && natural == save.natFailure()) return 3;
		return new CombatRules.Defense("", "", save.hit()).hits(total, save.target()) ? 1 : 0;
	}

	private static void roll(MinecraftServer server, ServerPlayerEntity player, LivingEntity e, State s) {
		CombatRules.Save save = s.rule == null ? null : s.rule.save();
		if (save == null || s.status != DOWNED) return;
		Combat c = CombatManager.get(e);
		if (c != null && !c.isTurnOf(e)) {
			player.sendMessage(Text.literal("Death saves are rolled on your own turn."), true);
			return;
		}
		int mod = 0;
		CharacterStore.Entry entry = entryOf(e);
		if (entry != null && !save.modifier().isBlank() && !save.modifier().equals("0")) {
			try {
				SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
				double v = f == null ? 0 : new SheetContext(f, CharacterData.parse(entry.json.deepCopy(), "")).number(save.modifier());
				mod = Double.isNaN(v) ? 0 : (int) Math.round(v);
			} catch (RuntimeException ex) {
				mod = 0;
			}
		}
		final int modifier = mod;
		DiceType type = diceOf(save.dice());
		String roller = e == player ? null : s.name;
		int total = DiceService.rollTotal(player, roller, save.label(), type, 1, modifier, 0, t -> {
			int o = outcome(save, t - modifier, t);
			return switch (o) {
				case 2 -> new DiceService.Verdict(1, "Natural " + save.natSuccess() + ": " + s.name + " gets up");
				case 3 -> new DiceService.Verdict(2, "Natural " + save.natFailure() + ": " + save.natFailures() + " failures");
				case 1 -> new DiceService.Verdict(1, "Success");
				default -> new DiceService.Verdict(2, "Failure");
			};
		});
		int o = outcome(save, total - modifier, total);
		String note;
		switch (o) {
			case 2 -> {
				String vital = SheetHealth.vitalId(e);
				if (vital != null && save.natHeal() > 0) SheetHealth.change(server, e, vital, save.natHeal());
				check(server, e);
				return; // up again: check() told everyone
			}
			case 3 -> {
				s.failures += save.natFailures();
				note = "Rolled a natural " + save.natFailure() + ".";
			}
			case 1 -> {
				s.successes++;
				note = "Success.";
			}
			default -> {
				s.failures++;
				note = "Failure.";
			}
		}
		if (s.successes >= save.successes()) s.status = STABLE;
		if (s.failures >= save.failures()) s.status = DEAD;
		announce(server, s, "rolled a " + save.label().toLowerCase(java.util.Locale.ROOT));
		send(server, e, s, true, note);
		if (c != null && c.isTurnOf(e)) c.endTurn();
	}
}
