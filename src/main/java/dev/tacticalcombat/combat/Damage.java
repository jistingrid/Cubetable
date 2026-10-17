package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.net.DamagePromptPayload;
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

/**
 * Damage rolled against a target waits for the target's owner to settle it: the player of a player character, a
 * Dungeon Master for everything else. They get a prompt ("X takes 5 damage") and choose full, half, heal or a custom
 * amount; the result is applied and announced in chat. With nobody to ask, the full damage is applied at once.
 */
public final class Damage {
	private record Prompt(long id, UUID target, String targetName, String attacker, String label, int amount, List<UUID> owners) {}

	/** A suggestion for how to settle damage: 1 Full, 2 Half. */
	public record Hint(int kind, String reason) {}

	private record Suggest(String entry, Hint hint, int tick) {}

	private static final Map<Long, Prompt> PROMPTS = new HashMap<>();
	private static final Map<Long, Hint> HINTS = new HashMap<>();
	/** What an entry's rules suggested for a target (a saving throw passed or failed), kept for a while for the damage to come. */
	private static final Map<UUID, Suggest> SUGGESTS = new HashMap<>();
	private static final int SUGGEST_TICKS = 2400;
	private static long nextId = 1;

	private Damage() {}

	public static void clear() {
		PROMPTS.clear();
		HINTS.clear();
		SUGGESTS.clear();
	}

	static String nameOf(Entity e) {
		return e instanceof ServerPlayerEntity p ? p.getName().getString() : e.getName().getString();
	}

	/** Ask the owner(s) of {@code target} what to do with {@code amount} damage dealt by {@code attacker}. */
	public static void offer(ServerPlayerEntity attacker, String attackerName, LivingEntity target, String label, int amount) {
		String from = attackerName == null || attackerName.isBlank() ? attacker.getName().getString() : attackerName;
		MinecraftServer server = attacker.getServer();
		if (server == null) return;
		List<ServerPlayerEntity> owners = new ArrayList<>();
		if (target instanceof ServerPlayerEntity tp) {
			owners.add(tp);
		} else {
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				if (Roles.isDm(p.getUuid())) owners.add(p);
			}
		}
		String name = nameOf(target);
		if (owners.isEmpty()) {
			apply(server, target, -amount, name);
			return;
		}
		long id = nextId++;
		List<UUID> uuids = new ArrayList<>();
		for (ServerPlayerEntity p : owners) uuids.add(p.getUuid());
		PROMPTS.put(id, new Prompt(id, target.getUuid(), name, from, label, amount, uuids));
		Hint hint = null;
		Suggest said = SUGGESTS.get(target.getUuid());
		if (said != null && server.getTicks() - said.tick() <= SUGGEST_TICKS && Strikes.entryName(label).equalsIgnoreCase(said.entry())) hint = said.hint();
		if (hint == null) hint = Conditions.incomingHint(server, target, amount); // a condition that says "half" (resistance ...)
		if (hint != null) HINTS.put(id, hint);
		send(server, id, name, from, label, amount, SheetHealth.damageNoun(target), hint, uuids);
	}

	private static void send(MinecraftServer server, long id, String name, String from, String label, int amount, String noun, Hint hint, List<UUID> owners) {
		DamagePromptPayload payload = new DamagePromptPayload(id, false, name, from, label, amount, noun,
				hint == null ? 0 : hint.kind(), hint == null ? "" : hint.reason());
		for (UUID u : owners) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(u);
			if (p != null) ServerPlayNetworking.send(p, payload);
		}
	}

	/**
	 * An entry's rules suggest how to settle its damage on {@code target} (a saving throw passed: half; failed: full).
	 * It marks the open prompt for that entry, or waits for the damage if it has not been rolled yet.
	 */
	public static void suggest(MinecraftServer server, LivingEntity target, String entry, int kind, String reason) {
		Hint hint = new Hint(kind, reason);
		SUGGESTS.put(target.getUuid(), new Suggest(entry, hint, server.getTicks()));
		for (Prompt p : PROMPTS.values()) {
			if (!p.target().equals(target.getUuid()) || !Strikes.entryName(p.label()).equalsIgnoreCase(entry)) continue;
			HINTS.put(p.id(), hint);
			send(server, p.id(), p.targetName(), p.attacker(), p.label(), p.amount(), SheetHealth.damageNoun(target), hint, p.owners());
		}
	}

	/** An owner pressed a button. mode: 0 full, 1 half, 2 heal, 3 custom damage, 4 custom heal. */
	public static void resolve(ServerPlayerEntity responder, long id, int mode, int custom) {
		Prompt p = PROMPTS.get(id);
		if (p == null || !p.owners().contains(responder.getUuid())) return;
		PROMPTS.remove(id);
		HINTS.remove(id);
		MinecraftServer server = responder.getServer();
		if (server == null) return;

		DamagePromptPayload close = new DamagePromptPayload(id, true, "", "", "", 0, "", 0, "");
		for (UUID u : p.owners()) {
			ServerPlayerEntity o = server.getPlayerManager().getPlayer(u);
			if (o != null && o != responder) ServerPlayNetworking.send(o, close);
		}

		int amount = Math.max(0, Math.min(9999, custom));
		int signed = switch (mode) {
			case 0 -> -p.amount();
			case 1 -> -(p.amount() / 2);
			case 2 -> p.amount();
			case 3 -> -amount;
			case 4 -> amount;
			default -> 0;
		};
		Entity target = find(server, p.target());
		if (!(target instanceof LivingEntity living) || !living.isAlive()) return;
		apply(server, living, signed, p.targetName());
	}

	private static Entity find(MinecraftServer server, UUID id) {
		for (ServerWorld w : server.getWorlds()) {
			Entity e = w.getEntity(id);
			if (e != null) return e;
		}
		return null;
	}

	/** A negative amount is damage, a positive one healing. Announced to everyone. */
	private static void apply(MinecraftServer server, LivingEntity target, int signed, String name) {
		int n = Math.abs(signed);
		boolean wasDown = Downed.isDown(target);
		if (signed < 0) {
			if (SheetHealth.damageBarId(target) != null) { // a player's Active Actor or an Actor's own sheet
				SheetHealth.damage(server, target, n);
				target.getWorld().sendEntityStatus(target, (byte) 2); // the hurt flash, without Minecraft damage
				if (target instanceof ServerPlayerEntity tp) SheetHealth.syncBar(tp);
			} else if (n > 0) {
				target.damage(target.getWorld().getDamageSources().generic(), n);
				Floaters.damage(target, n, target.getMaxHealth());
			}
			server.getPlayerManager().broadcast(Text.translatable("tacticalcombat.msg.damage_received", name, n, SheetHealth.damageNoun(target)), false);
		} else {
			if (SheetHealth.damageBarId(target) != null) {
				SheetHealth.change(server, target, SheetHealth.damageBarId(target), n);
				if (target instanceof ServerPlayerEntity tp) SheetHealth.syncBar(tp);
			} else {
				target.heal(n);
				Floaters.heal(target, n);
			}
			server.getPlayerManager().broadcast(Text.translatable("tacticalcombat.msg.damage_healed", name, n, SheetHealth.damageBarLabel(target)), false);
		}
		if (signed < 0) Downed.afterDamage(server, target, n, wasDown);
		else Downed.check(server, target);
		// condition rules: "I take damage", "I'm healed", "I go down"
		if (n > 0) Conditions.fire(server, target, signed < 0 ? "damaged" : "healed", java.util.Map.of("damage", n));
		if (!wasDown && Downed.isDown(target)) Conditions.fire(server, target, "downed", java.util.Map.of());
		if (signed < 0 && n > 0) {
			if (Downed.isDown(target)) Concentration.onDown(server, target);
			else Concentration.onDamaged(server, target, n);
		}
		Combat combat = CombatManager.get(target);
		if (combat != null) combat.sync();
	}
}
