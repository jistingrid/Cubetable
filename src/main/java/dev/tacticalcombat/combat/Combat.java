package dev.tacticalcombat.combat;

import dev.tacticalcombat.net.CombatStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A single turn based fight. Owns the initiative order, whose turn it is, and enforces the rules:
 * only the active combatant may move (limited distance) and attack (one action); everyone else is frozen.
 */
public final class Combat {
	private final ServerWorld world;
	private List<Combatant> order = new ArrayList<>();
	private int turn = 0;
	private int round = 1;
	private int tickCounter = 0;
	private final Set<ServerPlayerEntity> notified = new HashSet<>();

	private Combat(ServerWorld world) {
		this.world = world;
	}

	// ---------------------------------------------------------------- creation

	public static Combat start(ServerWorld world, Collection<ServerPlayerEntity> players, Collection<MobEntity> enemies) {
		Combat combat = new Combat(world);
		for (ServerPlayerEntity p : players) {
			combat.order.add(new Combatant(p, rollInitiative(world)));
		}
		for (MobEntity m : enemies) {
			combat.order.add(new Combatant(m, rollInitiative(world)));
		}
		combat.sortOrder();
		combat.turn = 0;
		combat.beginTurn();
		for (ServerPlayerEntity p : players) {
			p.sendMessage(Text.translatable("tacticalcombat.msg.combat_start"), true);
		}
		combat.sync();
		return combat;
	}

	private static int rollInitiative(ServerWorld world) {
		return 1 + world.getRandom().nextInt(20);
	}

	private void sortOrder() {
		order.sort(Comparator.<Combatant>comparingInt(c -> -c.initiative).thenComparing(c -> !c.isPlayer()));
	}

	// ---------------------------------------------------------------- queries

	public Combatant current() {
		return order.get(turn);
	}

	public Combatant get(Entity entity) {
		for (Combatant c : order) {
			if (c.entity == entity) return c;
		}
		return null;
	}

	public boolean contains(Entity entity) {
		return get(entity) != null;
	}

	/** True if {@code entity} is in this fight and it is currently its turn. */
	public boolean isTurnOf(Entity entity) {
		return !order.isEmpty() && current().entity == entity;
	}

	// ---------------------------------------------------------------- rules

	/**
	 * Called whenever a combatant of this fight deals damage. Returns false when the attack must be cancelled
	 * (not its turn, or action already spent); otherwise spends the action.
	 */
	public boolean tryUseAction(LivingEntity attacker) {
		Combatant c = get(attacker);
		if (c == null) return true;
		if (current() != c || c.actionUsed) return false;
		c.actionUsed = true;
		return true;
	}

	/** Ends the current turn and starts the next one. */
	public void endTurn() {
		Combatant cur = current();
		cur.anchor = cur.entity.getPos();
		turn++;
		if (turn >= order.size()) {
			turn = 0;
			round++;
		}
		beginTurn();
		sync();
	}

	private void beginTurn() {
		Combatant c = current();
		c.resetTurnResources(c.isPlayer() ? CombatConfig.PLAYER_MOVEMENT : CombatConfig.MOB_MOVEMENT);
		if (c.entity instanceof ServerPlayerEntity p) {
			p.sendMessage(Text.translatable("tacticalcombat.msg.your_turn"), true);
		}
	}

	// ---------------------------------------------------------------- tick

	/** @return true when the combat is over and should be removed. */
	public boolean tick() {
		tickCounter++;

		boolean currentRemoved = prune();
		if (isFinished()) return true;
		if (currentRemoved) {
			beginTurn();
			sync();
		}

		if (tickCounter % 10 == 0) recruit();

		Combatant cur = current();
		cur.ticksInTurn++;
		for (Combatant c : order) {
			if (c != cur) freeze(c);
		}
		if (cur.isPlayer()) {
			tickPlayerTurn(cur);
		} else {
			tickMobTurn(cur);
		}

		if (tickCounter % 2 == 0) sync();
		return false;
	}

	private boolean isValid(Combatant c) {
		LivingEntity e = c.entity;
		if (!e.isAlive() || e.isRemoved() || e.getWorld() != world) return false;
		if (e instanceof ServerPlayerEntity p) {
			if (p.isSpectator()) return false;
			return world.getServer().getPlayerManager().getPlayerList().contains(p);
		}
		return true;
	}

	/** Removes dead / gone combatants and keeps {@link #turn} pointing at the right entry. @return true if the current one was removed. */
	private boolean prune() {
		List<Combatant> kept = new ArrayList<>();
		int newTurn = 0;
		boolean currentRemoved = false;
		for (int i = 0; i < order.size(); i++) {
			Combatant c = order.get(i);
			boolean ok = isValid(c);
			if (i == turn) {
				newTurn = kept.size();
				currentRemoved = !ok;
			}
			if (ok) {
				kept.add(c);
			} else if (c.entity instanceof ServerPlayerEntity p) {
				sendInactive(p);
				notified.remove(p);
			}
		}
		order = kept;
		turn = newTurn;
		if (!order.isEmpty() && turn >= order.size()) {
			turn = 0;
			round++;
		}
		return currentRemoved;
	}

	private boolean isFinished() {
		List<Combatant> players = new ArrayList<>();
		List<Combatant> enemies = new ArrayList<>();
		for (Combatant c : order) {
			(c.isPlayer() ? players : enemies).add(c);
		}
		if (players.isEmpty() || enemies.isEmpty()) return true;

		double range2 = CombatConfig.LEAVE_RANGE * CombatConfig.LEAVE_RANGE;
		for (Combatant enemy : enemies) {
			for (Combatant player : players) {
				if (enemy.entity.squaredDistanceTo(player.entity) <= range2) return false;
			}
		}
		return true; // every enemy is far away from every player
	}

	/** Pulls newly aggroed hostiles and nearby players into the running fight. */
	private void recruit() {
		Set<LivingEntity> joiners = new LinkedHashSet<>();
		for (Combatant c : new ArrayList<>(order)) {
			if (!c.isPlayer()) continue;
			ServerPlayerEntity p = (ServerPlayerEntity) c.entity;

			Box box = p.getBoundingBox().expand(CombatConfig.JOIN_RANGE);
			joiners.addAll(world.getEntitiesByClass(MobEntity.class, box, m ->
					CombatManager.isEligibleEnemy(m)
							&& !CombatManager.isInCombat(m)
							&& m.getTarget() instanceof ServerPlayerEntity t
							&& contains(t)));

			joiners.addAll(world.getPlayers(o ->
					!o.isSpectator() && o.isAlive()
							&& !CombatManager.isInCombat(o)
							&& o.squaredDistanceTo(p) < 16.0 * 16.0));
		}
		for (LivingEntity e : joiners) {
			addCombatant(e);
		}
	}

	private void addCombatant(LivingEntity entity) {
		if (contains(entity)) return;
		Combatant cur = current();
		order.add(new Combatant(entity, rollInitiative(world)));
		sortOrder();
		turn = order.indexOf(cur);
		if (entity instanceof ServerPlayerEntity p) {
			p.sendMessage(Text.translatable("tacticalcombat.msg.combat_start"), true);
		}
		sync();
	}

	// ---------------------------------------------------------------- turn handling

	/** Keeps a waiting combatant exactly where it is. */
	private void freeze(Combatant c) {
		Vec3d a = c.anchor;
		LivingEntity e = c.entity;
		if (e instanceof ServerPlayerEntity p) {
			double dx = p.getX() - a.x;
			double dz = p.getZ() - a.z;
			if (dx * dx + dz * dz > 0.0025) {
				p.networkHandler.requestTeleport(a.x, p.getY(), a.z, p.getYaw(), p.getPitch());
			}
		} else if (e instanceof MobEntity m) {
			m.setTarget(null);
			m.getNavigation().stop();
			boolean flying = m instanceof FlyingEntity || m.hasNoGravity();
			double dx = m.getX() - a.x;
			double dz = m.getZ() - a.z;
			double dy = flying ? m.getY() - a.y : 0;
			if (dx * dx + dz * dz + dy * dy > 0.0025) {
				m.refreshPositionAndAngles(a.x, flying ? a.y : m.getY(), a.z, m.getYaw(), m.getPitch());
			}
			m.setVelocity(flying ? Vec3d.ZERO : new Vec3d(0, m.getVelocity().y, 0));
		}
	}

	private void tickPlayerTurn(Combatant c) {
		ServerPlayerEntity p = (ServerPlayerEntity) c.entity;
		spendMovement(c, p.getPos(), true);

		if (CombatConfig.PLAYER_TURN_TICKS > 0 && c.ticksInTurn >= CombatConfig.PLAYER_TURN_TICKS) {
			endTurn();
		}
	}

	private void tickMobTurn(Combatant c) {
		MobEntity mob = (MobEntity) c.entity;

		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive() || !contains(target)) {
			mob.setTarget(nearestPlayer(mob));
		}

		spendMovement(c, mob.getPos(), false);

		if (c.actionUsed && ++c.ticksSinceActionUsed >= CombatConfig.MOB_AFTER_ACTION_TICKS) {
			endTurn();
		} else if (c.moveExhausted && ++c.ticksSinceMoveExhausted >= CombatConfig.MOB_AFTER_MOVE_TICKS) {
			endTurn();
		} else if (c.ticksInTurn >= CombatConfig.MOB_TURN_MAX_TICKS) {
			endTurn();
		}
	}

	/** Counts horizontal movement against the budget; pins the entity at the limit once it is spent. */
	private void spendMovement(Combatant c, Vec3d pos, boolean player) {
		double dx = pos.x - c.anchor.x;
		double dz = pos.z - c.anchor.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 1.0E-4) return;

		double remaining = c.moveBudget - c.moveUsed;
		if (dist <= remaining) {
			c.moveUsed += dist;
			c.anchor = pos;
			if (c.moveBudget - c.moveUsed < 0.05) c.moveExhausted = true;
			return;
		}

		// Would exceed the budget: put the entity on the boundary and stop it there.
		double f = Math.max(0, remaining) / dist;
		double bx = c.anchor.x + dx * f;
		double bz = c.anchor.z + dz * f;
		c.moveUsed = c.moveBudget;
		c.moveExhausted = true;
		c.anchor = new Vec3d(bx, pos.y, bz);

		LivingEntity e = c.entity;
		if (player) {
			((ServerPlayerEntity) e).networkHandler.requestTeleport(bx, pos.y, bz, e.getYaw(), e.getPitch());
		} else {
			MobEntity m = (MobEntity) e;
			m.getNavigation().stop();
			m.refreshPositionAndAngles(bx, pos.y, bz, m.getYaw(), m.getPitch());
			m.setVelocity(0, m.getVelocity().y, 0);
		}
	}

	private ServerPlayerEntity nearestPlayer(MobEntity mob) {
		ServerPlayerEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Combatant c : order) {
			if (!(c.entity instanceof ServerPlayerEntity p) || !p.isAlive()) continue;
			double d = mob.squaredDistanceTo(p);
			if (d < bestDist) {
				bestDist = d;
				best = p;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- networking

	public void sync() {
		if (order.isEmpty()) return;

		List<CombatStatePayload.Entry> entries = new ArrayList<>();
		for (Combatant c : order) {
			LivingEntity e = c.entity;
			boolean player = e instanceof ServerPlayerEntity;
			entries.add(new CombatStatePayload.Entry(
					e.getId(),
					Registries.ENTITY_TYPE.getId(e.getType()),
					player ? e.getName().getString() : "",
					e.getHealth(),
					e.getMaxHealth(),
					!player,
					c.initiative));
		}

		Combatant cur = current();
		CombatStatePayload payload = new CombatStatePayload(true, round, turn,
				(float) cur.moveUsed, (float) cur.moveBudget, cur.actionUsed, cur.bonusActionUsed, entries);

		for (Combatant c : order) {
			if (c.entity instanceof ServerPlayerEntity p) {
				ServerPlayNetworking.send(p, payload);
				notified.add(p);
			}
		}
	}

	private static void sendInactive(ServerPlayerEntity p) {
		try {
			if (p.networkHandler != null) {
				ServerPlayNetworking.send(p, CombatStatePayload.inactive());
				p.sendMessage(Text.translatable("tacticalcombat.msg.combat_end"), true);
			}
		} catch (Exception ignored) {
			// player already disconnected
		}
	}

	/** Releases everyone. Call when the combat is removed. */
	public void end() {
		for (ServerPlayerEntity p : notified) {
			sendInactive(p);
		}
		notified.clear();
		for (Combatant c : order) {
			if (c.entity instanceof MobEntity m) {
				m.getNavigation().stop();
			}
		}
		order.clear();
	}
}
