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

	private static final Map<Long, Prompt> PROMPTS = new HashMap<>();
	private static long nextId = 1;

	private Damage() {}

	public static void clear() {
		PROMPTS.clear();
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
		DamagePromptPayload payload = new DamagePromptPayload(id, false, name, from, label, amount);
		for (ServerPlayerEntity p : owners) ServerPlayNetworking.send(p, payload);
	}

	/** An owner pressed a button. mode: 0 full, 1 half, 2 heal, 3 custom damage, 4 custom heal. */
	public static void resolve(ServerPlayerEntity responder, long id, int mode, int custom) {
		Prompt p = PROMPTS.get(id);
		if (p == null || !p.owners().contains(responder.getUuid())) return;
		PROMPTS.remove(id);
		MinecraftServer server = responder.getServer();
		if (server == null) return;

		DamagePromptPayload close = new DamagePromptPayload(id, true, "", "", "", 0);
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
			if (SheetHealth.of(target) != null) { // a player's Active Actor or an Actor's own sheet
				SheetHealth.damage(server, target, n);
				target.getWorld().sendEntityStatus(target, (byte) 2); // the hurt flash, without Minecraft damage
				if (target instanceof ServerPlayerEntity tp) SheetHealth.syncBar(tp);
			} else if (n > 0) {
				target.damage(target.getWorld().getDamageSources().generic(), n);
			}
			server.getPlayerManager().broadcast(Text.translatable("tacticalcombat.msg.damage_received", name, n), false);
		} else {
			if (SheetHealth.of(target) != null) {
				SheetHealth.change(server, target, SheetHealth.vitalId(target), n);
				if (target instanceof ServerPlayerEntity tp) SheetHealth.syncBar(tp);
			} else {
				target.heal(n);
			}
			server.getPlayerManager().broadcast(Text.translatable("tacticalcombat.msg.damage_healed", name, n), false);
		}
		if (signed < 0) Downed.afterDamage(server, target, n, wasDown);
		else Downed.check(server, target);
		Combat combat = CombatManager.get(target);
		if (combat != null) combat.sync();
	}
}
