package dev.tacticalcombat.combat;

import dev.tacticalcombat.grid.Grid;
import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.GridPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * A single turn based fight. Owns the initiative order, whose turn it is, and enforces the rules:
 * only the active combatant may move and attack; everyone else is frozen. Players move square by square
 * (the server computes the reachable squares and slides the player along the chosen path); mobs still walk
 * freely but are limited to a movement budget.
 */
public final class Combat {
	/** Teleports that must not touch the client's camera / look direction. */
	private static final Set<PositionFlag> KEEP_LOOK = EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT);

	private final ServerWorld world;
	private List<Combatant> order = new ArrayList<>();
	private int turn = 0;
	private int round = 1;
	private int tickCounter = 0;
	private final Set<ServerPlayerEntity> notified = new HashSet<>();

	/** Squares the active player may walk to right now; null when it is not a standing player's turn. */
	private Grid.Result moveGrid;
	/** Squares enemies could reach / hit (red area). */
	private Set<Long> threat = Set.of();
	private boolean gridDirty;

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
		cur.path = null;
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
		gridDirty = false;
		refreshGrid();
	}

	// ---------------------------------------------------------------- player requests

	/** The active player clicked a square. */
	public void requestMove(ServerPlayerEntity player, BlockPos target) {
		Combatant c = get(player);
		if (c == null || current() != c || c.moving() || moveGrid == null) return;

		Integer idx = moveGrid.index.get(target.asLong());
		if (idx == null) return;
		Grid.Node node = moveGrid.nodes.get(idx);
		if (!node.endable || node.cost <= 0) return;
		if (c.moveUsed + node.cost > c.moveBudget + 1.0E-6) return;

		LinkedList<Vec3d> waypoints = new LinkedList<>();
		int i = idx;
		while (i > 0) {
			Grid.Node n = moveGrid.nodes.get(i);
			waypoints.addFirst(Grid.centerOf(world, BlockPos.fromLong(n.pos)));
			i = n.parent;
		}

		c.path = waypoints;
		c.pathIdx = 0;
		c.pathPos = player.getPos();
		c.moveUsed += node.cost;

		moveGrid = null;
		threat = Set.of();
		sendGrid(); // clears the highlights while walking
		sync();
	}

	/** The active player clicked an enemy: spend the action on a melee hit. */
	public void requestAttack(ServerPlayerEntity player, int entityId) {
		Combatant c = get(player);
		if (c == null || current() != c || c.moving() || c.actionUsed) return;

		Entity e = world.getEntityById(entityId);
		if (!(e instanceof LivingEntity target) || !target.isAlive()) return;
		Combatant tc = get(target);
		if (tc == null || tc.isPlayer()) return;

		if (Grid.distanceToBox(player.getEyePos(), target.getBoundingBox()) > Grid.ATTACK_REACH) {
			player.sendMessage(Text.translatable("tacticalcombat.msg.too_far"), true);
			return;
		}

		// turn to face the target (absolute rotation, so the client's model turns too)
		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		double dy = target.getEyeY() - player.getEyeY();
		float yaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90.0f;
		float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.29577951308232);
		player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);

		player.swingHand(Hand.MAIN_HAND, true);
		player.attack(target); // the ALLOW_DAMAGE hook spends the action when the hit lands
		c.actionUsed = true;
		sync();
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
			// everyone is held in place; the only exceptions are a mob on its own turn and a player mid-walk
			if (c != cur || (c.isPlayer() && !c.moving())) freeze(c);
		}
		if (cur.isPlayer()) {
			if (cur.moving()) {
				tickMove(cur);
			} else {
				tickPlayerTurn(cur);
			}
		} else {
			tickMobTurn(cur);
		}

		if (gridDirty && !current().moving()) {
			gridDirty = false;
			refreshGrid();
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
		// an enemy that drifted (or was knocked) out of the players' vicinity leaves the fight
		return isNearAnyPlayer(e, CombatConfig.LEAVE_RANGE, CombatConfig.LEAVE_VERTICAL);
	}

	private boolean isNearAnyPlayer(LivingEntity e, double horizontal, double vertical) {
		for (Combatant c : order) {
			if (c.isPlayer() && CombatManager.isNear(e, c.entity, horizontal, vertical)) return true;
		}
		return false;
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
			} else {
				gridDirty = true;
				if (c.entity instanceof ServerPlayerEntity p) {
					sendInactive(p);
					notified.remove(p);
				}
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
		// enemies that are out of range are already removed by prune(), so "no enemies left" covers that case
		return players.isEmpty() || enemies.isEmpty();
	}

	/** Pulls newly aggroed hostiles and nearby players into the running fight. */
	private void recruit() {
		Set<LivingEntity> joiners = new LinkedHashSet<>();
		for (Combatant c : new ArrayList<>(order)) {
			if (!c.isPlayer()) continue;
			ServerPlayerEntity p = (ServerPlayerEntity) c.entity;

			Box box = p.getBoundingBox().expand(
					CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL, CombatConfig.JOIN_RANGE);
			joiners.addAll(world.getEntitiesByClass(MobEntity.class, box, m ->
					CombatManager.isEligibleEnemy(m)
							&& !CombatManager.isInCombat(m)
							&& m.getTarget() instanceof ServerPlayerEntity t
							&& contains(t)
							&& CombatManager.isNear(m, p, CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL)));

			joiners.addAll(world.getPlayers(o ->
					!o.isSpectator() && o.isAlive()
							&& !CombatManager.isInCombat(o)
							&& CombatManager.isNear(o, p, CombatConfig.JOIN_RANGE, CombatConfig.JOIN_VERTICAL)));
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
		gridDirty = true;
		if (entity instanceof ServerPlayerEntity p) {
			p.sendMessage(Text.translatable("tacticalcombat.msg.combat_start"), true);
		}
		sync();
	}

	// ---------------------------------------------------------------- turn handling

	/** Slides a player (without touching the client's camera / look direction). */
	private static void teleportKeepLook(ServerPlayerEntity p, double x, double y, double z) {
		p.networkHandler.requestTeleport(x, y, z, p.getYaw(), p.getPitch(), KEEP_LOOK);
	}

	/** Keeps a waiting combatant exactly where it is. */
	private void freeze(Combatant c) {
		Vec3d a = c.anchor;
		LivingEntity e = c.entity;
		if (e instanceof ServerPlayerEntity p) {
			double dx = p.getX() - a.x;
			double dz = p.getZ() - a.z;
			if (dx * dx + dz * dz > 0.0625) {
				teleportKeepLook(p, a.x, p.getY(), a.z);
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
		if (CombatConfig.PLAYER_TURN_TICKS > 0 && c.ticksInTurn >= CombatConfig.PLAYER_TURN_TICKS) {
			endTurn();
		}
	}

	/** Slides the active player along the chosen path, one step per tick. */
	private void tickMove(Combatant c) {
		ServerPlayerEntity p = (ServerPlayerEntity) c.entity;
		Vec3d pos = c.pathPos;
		double remaining = CombatConfig.MOVE_SPEED;

		while (remaining > 1.0E-6 && c.pathIdx < c.path.size()) {
			Vec3d target = c.path.get(c.pathIdx);
			Vec3d delta = target.subtract(pos);
			double len = delta.length();
			if (len <= remaining) {
				pos = target;
				remaining -= len;
				c.pathIdx++;
			} else {
				pos = pos.add(delta.multiply(remaining / len));
				remaining = 0;
			}
		}

		c.pathPos = pos;
		teleportKeepLook(p, pos.x, pos.y, pos.z);
		p.fallDistance = 0;

		if (c.pathIdx >= c.path.size()) {
			c.path = null;
			c.pathIdx = 0;
			c.anchor = pos;
			gridDirty = true; // recompute the squares from the new position with the leftover movement
			sync();
		}
	}

	private void tickMobTurn(Combatant c) {
		MobEntity mob = (MobEntity) c.entity;

		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive() || !contains(target)) {
			mob.setTarget(nearestPlayer(mob));
		}

		spendMovement(c, mob.getPos());

		if (c.actionUsed && ++c.ticksSinceActionUsed >= CombatConfig.MOB_AFTER_ACTION_TICKS) {
			endTurn();
		} else if (c.moveExhausted && ++c.ticksSinceMoveExhausted >= CombatConfig.MOB_AFTER_MOVE_TICKS) {
			endTurn();
		} else if (c.ticksInTurn >= CombatConfig.MOB_TURN_MAX_TICKS) {
			endTurn();
		}
	}

	/** Counts a mob's horizontal movement against its budget; pins it at the limit once it is spent. */
	private void spendMovement(Combatant c, Vec3d pos) {
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

		double f = Math.max(0, remaining) / dist;
		double bx = c.anchor.x + dx * f;
		double bz = c.anchor.z + dz * f;
		c.moveUsed = c.moveBudget;
		c.moveExhausted = true;
		c.anchor = new Vec3d(bx, pos.y, bz);

		MobEntity m = (MobEntity) c.entity;
		m.getNavigation().stop();
		m.refreshPositionAndAngles(bx, pos.y, bz, m.getYaw(), m.getPitch());
		m.setVelocity(0, m.getVelocity().y, 0);
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

	// ---------------------------------------------------------------- grid

	/** Recomputes the walkable squares for the active player and the enemy threat area, then sends them. */
	private void refreshGrid() {
		if (order.isEmpty()) return;
		Combatant cur = current();
		moveGrid = null;
		threat = Set.of();

		if (cur.isPlayer() && !cur.moving()) {
			Set<Long> enemyCells = new HashSet<>();
			Set<Long> occupied = new HashSet<>();
			for (Combatant o : order) {
				if (o == cur) continue;
				long key = Grid.cellOf(o.entity).asLong();
				occupied.add(key);
				if (!o.isPlayer()) enemyCells.add(key);
			}

			int remaining = Math.max(0, (int) Math.floor(cur.moveBudget - cur.moveUsed + 1.0E-6));
			Grid.Result r = Grid.reachable(world, Grid.cellOf(cur.entity), remaining, enemyCells);
			for (Grid.Node n : r.nodes) {
				if (n.cost == 0 || occupied.contains(n.pos)) n.endable = false;
			}
			moveGrid = r;
			threat = computeThreat(cur);
		}
		sendGrid();
	}

	/** Every square a hostile could walk to (or hit from) on its next turn. */
	private Set<Long> computeThreat(Combatant viewer) {
		Set<Long> playerCells = new HashSet<>();
		for (Combatant o : order) {
			if (o.isPlayer()) playerCells.add(Grid.cellOf(o.entity).asLong());
		}

		Set<Long> out = new HashSet<>();
		double range2 = CombatConfig.THREAT_SCAN_RANGE * CombatConfig.THREAT_SCAN_RANGE;
		int scanned = 0;
		for (Combatant o : order) {
			if (o.isPlayer() || o.entity.squaredDistanceTo(viewer.entity) > range2) continue;
			if (++scanned > CombatConfig.THREAT_MAX_ENEMIES) break;

			Grid.Result r = Grid.reachable(world, Grid.cellOf(o.entity), (int) CombatConfig.MOB_MOVEMENT, playerCells);
			for (Grid.Node n : r.nodes) {
				out.add(n.pos);
				BlockPos b = BlockPos.fromLong(n.pos);
				for (Direction dir : Direction.Type.HORIZONTAL) {
					BlockPos s = Grid.step(world, b, dir); // squares next to a reachable one can be hit
					if (s != null) out.add(s.asLong());
				}
			}
		}
		return out;
	}

	private void sendGrid() {
		if (order.isEmpty()) return;
		Combatant cur = current();
		GridPayload active = moveGrid == null ? GridPayload.empty() : GridPayload.of(moveGrid, threat);
		GridPayload none = GridPayload.empty();
		for (Combatant c : order) {
			if (c.entity instanceof ServerPlayerEntity p) {
				ServerPlayNetworking.send(p, c == cur ? active : none);
				notified.add(p);
			}
		}
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
				ServerPlayNetworking.send(p, GridPayload.empty());
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
