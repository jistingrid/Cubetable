package dev.tacticalcombat.combat;

import dev.tacticalcombat.actor.ActorRecord;
import dev.tacticalcombat.actor.ActorRegistry;
import dev.tacticalcombat.character.Actors;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.net.RestPayload;
import dev.tacticalcombat.net.RestPromptPayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.Expr;
import dev.tacticalcombat.sheet.RestMath;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resting, as the game's pack describes it ({@code combat.rest}). A rest sets stored values and refills counters from
 * formulas, gives hit dice back, ends the conditions whose duration is "until a rest", ends concentration, and, when a
 * Dungeon Master calls it for the party, moves the clock. A short rest then opens a prompt where the player spends hit
 * dice one at a time: each is rolled and heals the bar the pack names. The sheet's rest buttons, the Dungeon Master's
 * Rest tool and {@code /tbc rest} all end up here.
 */
public final class Rests {
	/** The rest a player is spending hit dice in: the sheet's server id and the rest's label. */
	private record Open(String entryId, String typeId, String label, String lastRoll) {}

	private static final Map<UUID, Open> OPEN = new HashMap<>();

	private Rests() {}

	// ---------------------------------------------------------------- requests

	/** A player's (or a Dungeon Master's) request from the client. */
	public static void request(ServerPlayerEntity player, RestPayload p) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		switch (p.op()) {
			case RestPayload.REQUEST -> {
				CharacterStore.Entry entry = p.link().isEmpty() ? Actors.entryOf(player.getUuid()) : CharacterStore.get(p.link());
				if (entry == null) {
					player.sendMessage(Text.literal("No character on the server to rest."), true);
					return;
				}
				if (!entry.owner.equals(player.getUuid()) && !Roles.isDm(player.getUuid())) {
					player.sendMessage(Text.literal("That character is not yours."), true);
					return;
				}
				String problem = start(server, entry, p.arg(), false, player);
				if (problem != null) player.sendMessage(Text.literal(problem), true);
			}
			case RestPayload.PARTY -> {
				if (!Roles.isDm(player.getUuid())) return;
				String report = party(server, p.arg());
				player.sendMessage(Text.literal(report), false);
			}
			case RestPayload.SPEND -> spend(player);
			case RestPayload.DONE -> done(player);
			default -> {
			}
		}
	}

	/** Every player with an Active Actor rests (a Dungeon Master calls it). Returns a line saying who did. */
	public static String party(MinecraftServer server, String typeId) {
		int done = 0;
		String label = typeId;
		StringBuilder skipped = new StringBuilder();
		boolean clockDone = false;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.isSpectator() || Roles.isDm(p.getUuid())) continue;
			CharacterStore.Entry entry = Actors.entryOf(p.getUuid());
			if (entry == null) continue;
			CombatRules.RestType type = typeOf(entry, typeId);
			if (type != null) label = type.label();
			String problem = start(server, entry, typeId, !clockDone, p);
			if (problem == null) {
				done++;
				if (type != null && !type.clock().isEmpty()) clockDone = true;
			} else {
				if (skipped.length() > 0) skipped.append("; ");
				skipped.append(p.getGameProfile().getName()).append(": ").append(problem);
			}
		}
		server.getPlayerManager().broadcast(Text.literal("The party takes a " + label.toLowerCase(java.util.Locale.ROOT) + "."), false);
		return done + " rested" + (skipped.length() > 0 ? ". Skipped - " + skipped : ".");
	}

	// ---------------------------------------------------------------- the rest itself

	private static CombatRules.RestType typeOf(CharacterStore.Entry entry, String typeId) {
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		return f == null || f.combat == null || f.combat.rest == null ? null : f.combat.rest.type(typeId);
	}

	/** The creature that wears this sheet right now (a player's Active Actor, or an Actor body), or null. */
	private static LivingEntity creatureOf(MinecraftServer server, CharacterStore.Entry entry) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			CharacterStore.Entry e = Actors.entryOf(p.getUuid());
			if (e != null && e.id.equals(entry.id)) return p;
		}
		for (ActorRecord r : ActorRegistry.all()) {
			LivingEntity body = ActorRegistry.bodyOf(server, r);
			CharacterStore.Entry e = body == null ? null : ActorRegistry.sheetEntryOf(body);
			if (e != null && e.id.equals(entry.id)) return body;
		}
		return null;
	}

	/**
	 * Takes the rest on the sheet {@code entry}. {@code byDm}: the clock may move. {@code asker}: who gets the hit dice prompt
	 * (the player the sheet belongs to). Returns null, or why it could not be taken.
	 */
	public static String start(MinecraftServer server, CharacterStore.Entry entry, String typeId, boolean byDm, ServerPlayerEntity asker) {
		SheetFormat f = SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		if (f == null || f.combat == null || f.combat.rest == null) return "This game has no rests (its pack does not say how).";
		CombatRules.Rest rest = f.combat.rest;
		CombatRules.RestType type = rest.type(typeId);
		if (type == null) return "This game has no rest called '" + typeId + "'.";
		LivingEntity who = creatureOf(server, entry);
		if (!rest.inCombat() && who != null && CombatManager.get(who) != null) return "Not in the middle of a fight.";

		CharacterData c;
		try {
			c = CharacterData.parse(entry.json.deepCopy(), "");
		} catch (RuntimeException e) {
			return "That sheet cannot be read.";
		}
		SheetContext sc = new SheetContext(f, c);
		RestMath.apply(sc, type.set(), type.refill());
		int regained = RestMath.regainHitDice(sc, rest.hitDice(), type);
		Conditions.saveSheet(server, entry, c);

		String name = c.texts.getOrDefault("name", who == null ? "Someone" : Damage.nameOf(who));
		int ended = 0;
		if (who != null) {
			if (!type.clearUnits().isEmpty()) ended = Conditions.endByUnit(server, who, new HashSet<>(type.clearUnits()));
			if (type.endConcentration() && Concentration.isConcentrating(who)) Concentration.end(server, who, "rested");
			if (who instanceof ServerPlayerEntity pp) SheetHealth.syncBar(pp);
			Downed.check(server, who);
			Combat combat = CombatManager.get(who);
			if (combat != null) combat.sync();
		}
		if (byDm && !type.clock().isEmpty()) Ambience.skipTo(server, type.clock());

		StringBuilder line = new StringBuilder(name + " takes a " + type.label().toLowerCase(java.util.Locale.ROOT));
		if (regained > 0) line.append(", ").append(regained).append(regained == 1 ? " hit die" : " hit dice").append(" back");
		if (ended > 0) line.append(", ").append(ended).append(ended == 1 ? " effect" : " effects").append(" ended");
		server.getPlayerManager().broadcast(Text.literal(line + "."), false);

		ServerPlayerEntity owner = asker != null && who == asker ? asker : who instanceof ServerPlayerEntity pl ? pl : null;
		if (type.spendHitDice() && rest.hitDice() != null && owner != null && RestMath.hitDiceLeft(sc, rest.hitDice()) > 0) {
			OPEN.put(owner.getUuid(), new Open(entry.id, type.id(), type.label(), ""));
			sendPrompt(owner, sc, rest.hitDice(), OPEN.get(owner.getUuid()));
		}
		return null;
	}

	// ---------------------------------------------------------------- hit dice

	private static void sendPrompt(ServerPlayerEntity player, SheetContext sc, CombatRules.HitDice hd, Open open) {
		String bar = hd.bar().isEmpty() ? SheetHealth.damageBarId(player) : hd.bar();
		String health = "";
		CombatRules.Bar b = null;
		if (sc.format.combat != null && bar != null) for (CombatRules.Bar each : sc.format.combat.bars) if (each.id().equalsIgnoreCase(bar)) b = each;
		if (b != null && !b.max().isBlank()) {
			double now = sc.character.values.getOrDefault(b.now(), 0.0);
			double max = sc.number(b.max());
			health = b.label() + " " + (int) Math.round(now) + " / " + (int) Math.round(max);
		}
		ServerPlayNetworking.send(player, new RestPromptPayload(false, open.label(), RestMath.hitDiceLeft(sc, hd),
				RestMath.hitDiceMax(sc, hd), RestMath.dieSides(sc, hd), health, open.lastRoll()));
	}

	/** The player spends one hit die: it is rolled with the pack's formula and heals the pack's bar. */
	private static void spend(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		Open open = OPEN.get(player.getUuid());
		CharacterStore.Entry entry = open == null ? null : CharacterStore.get(open.entryId());
		SheetFormat f = entry == null ? null : SheetLibrary.FORMATS.get(entry.json.has("format") ? entry.json.get("format").getAsString() : "");
		if (server == null || f == null || f.combat == null || f.combat.rest == null || f.combat.rest.hitDice() == null) {
			OPEN.remove(player.getUuid());
			ServerPlayNetworking.send(player, RestPromptPayload.closed());
			return;
		}
		CombatRules.HitDice hd = f.combat.rest.hitDice();
		CharacterData c;
		try {
			c = CharacterData.parse(entry.json.deepCopy(), "");
		} catch (RuntimeException e) {
			return;
		}
		SheetContext sc = new SheetContext(f, c);
		int left = RestMath.hitDiceLeft(sc, hd);
		if (left <= 0) {
			done(player);
			return;
		}
		c.values.put(hd.store(), (double) (left - 1));
		Conditions.saveSheet(server, entry, c);

		int heal;
		String formula = RestMath.healFormula(sc, hd);
		try {
			Expr.Roll plan = sc.plan(formula);
			heal = Math.max(0, DiceService.rollNatural(player, player.getGameProfile().getName(), "Hit die", plan.type(), plan.count(), plan.modifier(), 0, null));
		} catch (RuntimeException e) {
			Conditions.warn(server, "Hit dice: the formula '" + hd.heal() + "' does not work.");
			heal = 0;
		}
		String bar = hd.bar().isEmpty() ? SheetHealth.damageBarId(player) : hd.bar();
		if (bar != null && heal > 0) SheetHealth.change(server, player, bar, heal);
		SheetHealth.syncBar(player);
		Downed.check(server, player);

		CharacterStore.Entry fresh = CharacterStore.get(open.entryId());
		SheetContext after = fresh == null ? sc : new SheetContext(f, CharacterData.parse(fresh.json.deepCopy(), ""));
		Open next = new Open(open.entryId(), open.typeId(), open.label(), "Healed " + heal);
		OPEN.put(player.getUuid(), next);
		sendPrompt(player, after, hd, next);
	}

	private static void done(ServerPlayerEntity player) {
		if (OPEN.remove(player.getUuid()) != null) ServerPlayNetworking.send(player, RestPromptPayload.closed());
	}

	public static void forget(UUID player) {
		OPEN.remove(player);
	}
}
