package dev.tacticalcombat.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Keeps track of every running {@link Combat}, starts new ones and exposes the rule checks used by the event hooks. */
public final class CombatManager {
	private static final List<Combat> COMBATS = new ArrayList<>();

	private CombatManager() {}

	// ---------------------------------------------------------------- lookup

	public static Combat get(Entity entity) {
		for (Combat c : COMBATS) {
			if (c.contains(entity)) return c;
		}
		return null;
	}

	/** The fight a Dungeon Master who is not in one watches and runs (the oldest running fight). */
	public static Combat primary() {
		return COMBATS.isEmpty() ? null : COMBATS.get(0);
	}

	public static boolean isInCombat(Entity entity) {
		return get(entity) != null;
	}

	public static boolean isEligibleEnemy(MobEntity m) {
		return m.isAlive() && dev.tacticalcombat.actor.ActorRegistry.isHostileBody(m);
	}

	// ---------------------------------------------------------------- tick / detection

	public static void tick(MinecraftServer server) {
		Iterator<Combat> it = COMBATS.iterator();
		while (it.hasNext()) {
			Combat combat = it.next();
			if (combat.tick()) {
				combat.end();
				it.remove();
			}
		}

	}

	/**
	 * True if {@code a} and {@code b} are in the same area: within {@code horizontal} blocks on the ground
	 * and within {@code vertical} blocks of each other in height (so a cave far below does not count).
	 */
	public static boolean isNear(Entity a, Entity b, double horizontal, double vertical) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz <= horizontal * horizontal && Math.abs(a.getY() - b.getY()) <= vertical;
	}

	private static boolean canFight(ServerPlayerEntity p) {
		return p.isAlive() && !p.isSpectator();
	}

	/**
	 * Starts a combat around {@code center}.
	 * @param onlyAggroed only take hostiles that currently target a player (automatic start); the command passes false
	 * @return the new combat, or null if there was nobody to fight
	 */
	public static Combat startAround(ServerWorld world, ServerPlayerEntity center, boolean onlyAggroed) {
		Box area = center.getBoundingBox().expand(
				CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL, CombatConfig.JOIN_RANGE);

		List<MobEntity> enemies = world.getEntitiesByClass(MobEntity.class, area, m ->
				isEligibleEnemy(m) && !isInCombat(m)
						&& (!onlyAggroed || m.getTarget() instanceof PlayerEntity)
						&& isNear(m, center, CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL));
		if (enemies.isEmpty()) return null;

		Set<ServerPlayerEntity> party = new LinkedHashSet<>();
		party.add(center);
		party.addAll(world.getPlayers(p -> canFight(p) && !isInCombat(p)
				&& isNear(p, center, CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL)));

		Combat combat = Combat.start(world, party, enemies);
		COMBATS.add(combat);
		return combat;
	}

	/**
	 * A Dungeon Master starts an encounter: the party is gathered around them (or, failing that, around the nearest
	 * other player) and every hostile creature near it joins. Null when a fight is already running or there is nobody
	 * to fight.
	 */
	public static Combat startFor(ServerPlayerEntity dm) {
		if (primary() != null || !(dm.getWorld() instanceof ServerWorld world)) return null;
		List<ServerPlayerEntity> centers = new ArrayList<>();
		if (canFight(dm)) centers.add(dm);
		List<ServerPlayerEntity> others = new ArrayList<>(world.getPlayers(p -> p != dm && canFight(p)));
		others.sort(java.util.Comparator.comparingDouble(p -> p.squaredDistanceTo(dm)));
		centers.addAll(others);
		for (ServerPlayerEntity center : centers) {
			Combat combat = startAround(world, center, false);
			if (combat != null) return combat;
		}
		return null;
	}

	public static void endCombat(Combat combat) {
		combat.end();
		COMBATS.remove(combat);
	}

	public static void clear() {
		for (Combat c : COMBATS) c.end();
		COMBATS.clear();
	}

	// ---------------------------------------------------------------- rule hooks

	/** ALLOW_DAMAGE hook: gate attacks of combatants by turn and action economy. */
	public static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
		if (source.getAttacker() instanceof LivingEntity attacker && attacker != victim) {
			Combat combat = get(attacker);
			if (combat != null) {
				boolean ok = combat.tryUseAction(attacker);
				if (!ok && attacker instanceof ServerPlayerEntity p) {
					p.sendMessage(Text.translatable(combat.isTurnOf(p)
							? "tacticalcombat.msg.no_action"
							: "tacticalcombat.msg.not_your_turn"), true);
				}
				return ok;
			}
		}
		return true;
	}

	/** True if the player may use items / interact right now (not in combat, or it is their turn). */
	public static boolean canActNow(PlayerEntity player) {
		Combat combat = get(player);
		return combat == null || combat.isTurnOf(player);
	}

	public static void notifyNotYourTurn(PlayerEntity player) {
		if (player instanceof ServerPlayerEntity p) {
			p.sendMessage(Text.translatable("tacticalcombat.msg.not_your_turn"), true);
		}
	}

	/** The encounter window asked for something (roll initiative, reorder, start ...). */
	public static void requestEncounter(ServerPlayerEntity player, int op, int entityId, int value) {
		Combat combat = get(player);
		if (combat == null && dev.tacticalcombat.character.Roles.isDm(player.getUuid())) combat = primary(); // a DM running a fight from outside it
		if (combat != null) combat.encounterAction(player, op, entityId, value);
	}

	public static void requestBuyMove(ServerPlayerEntity player, String id) {
		Combat combat = get(player);
		if (combat != null) combat.requestBuyMove(player, id);
	}

	/** A player clicked a grid square to walk to. */
	public static void requestMove(ServerPlayerEntity player, BlockPos target) {
		Combat combat = get(player);
		if (combat != null) combat.requestMove(player, target);
	}

	/** A player clicked an enemy to attack it. */
	public static void requestTarget(ServerPlayerEntity player, int entityId) {
		Combat combat = get(player);
		if (combat != null) combat.setTarget(player, entityId);
	}

	public static void requestAttack(ServerPlayerEntity player, int entityId) {
		Combat combat = get(player);
		if (combat != null) combat.requestAttack(player, entityId);
	}

	/** Called when a player presses the End Turn key or runs /tbc endturn. */
	public static void requestEndTurn(ServerPlayerEntity player) {
		Combat combat = get(player);
		if (combat != null && combat.isTurnOf(player)) {
			combat.endTurn();
		}
	}
}
