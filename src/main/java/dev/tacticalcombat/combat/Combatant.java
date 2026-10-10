package dev.tacticalcombat.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One participant of a {@link Combat}: a player or a hostile mob, plus its per-turn resources. */
public final class Combatant {
	public final LivingEntity entity;
	/** Server id of the creature's NPC sheet ("" none); looked up once. */
	public String npcSheetId = "";
	public boolean npcChecked;
	public int initiative;
	/** Initiative has been rolled (or set by the Dungeon Master); until then the entry waits at the end of the order. */
	public boolean rolled;
	/** Breaks ties between equal initiative results (higher goes first). */
	public double tiebreak;
	/** This combatant's own initiative rule (its game's pack), which decides whether it has a roll button. */
	public InitiativeRule rule = InitiativeRule.DEFAULT;

	/** Where the combatant is held (waiting / standing still) or the last accepted position (mob turn). */
	public Vec3d anchor;

	/** Squares (players) or blocks (mobs) of movement. */
	public double moveBudget;
	public double moveUsed;
	/** Per-turn resources (action, bonus ...): what is left, and what the turn started with. */
	public final Map<String, Integer> left = new LinkedHashMap<>();
	public final Map<String, Integer> max = new LinkedHashMap<>();
	/** What this combatant may buy with its resources to move further (from its sheet's pack). */
	public TurnSetup setup = TurnSetup.basic(0);
	/** A bought move whose unused distance is lost: the budget is cut to what was used once the move is accepted. */
	public boolean lostAfterMove;

	public int ticksInTurn;
	public int ticksSinceActionUsed;
	public int ticksSinceMoveExhausted;
	public boolean moveExhausted;

	/** Mob only: the turn has been planned (destination chosen). */
	public boolean planned;
	/** Mob only: can not use the grid (flying, swimming...), so it walks freely under a block budget. */
	public boolean freeWalk;
	/** Mob only: ticks spent in the action phase (after the move). */
	public int ticksInAction;

	/** Waypoints (square centres) of the grid move the server is currently carrying out; null when standing. */
	public List<Vec3d> path;
	public int pathIdx;
	public Vec3d pathPos;

	public Combatant(LivingEntity entity, int initiative) {
		this.entity = entity;
		this.initiative = initiative;
		this.anchor = entity.getPos();
	}

	public boolean isPlayer() {
		return entity instanceof ServerPlayerEntity;
	}

	public boolean moving() {
		return path != null;
	}

	public int resource(String id) {
		return left.getOrDefault(id, 0);
	}

	public boolean canPay(Map<String, Integer> cost) {
		for (Map.Entry<String, Integer> e : cost.entrySet()) {
			if (resource(e.getKey()) < e.getValue()) return false;
		}
		return true;
	}

	public void pay(Map<String, Integer> cost) {
		for (Map.Entry<String, Integer> e : cost.entrySet()) {
			left.merge(e.getKey(), -e.getValue(), Integer::sum);
		}
	}

	/** The main "action" is gone (or the game has none to give). */
	public boolean actionUsed() {
		return resource("action") <= 0;
	}

	public void spendAction() {
		if (resource("action") > 0) left.merge("action", -1, Integer::sum);
	}

	public void resetTurnResources(TurnSetup setup) {
		this.setup = setup;
		this.moveBudget = setup.pool;
		this.moveUsed = 0;
		this.lostAfterMove = false;
		this.left.clear();
		this.left.putAll(setup.resources);
		this.max.clear();
		this.max.putAll(setup.resources);
		this.ticksInTurn = 0;
		this.ticksSinceActionUsed = 0;
		this.ticksSinceMoveExhausted = 0;
		this.moveExhausted = false;
		this.planned = false;
		this.freeWalk = false;
		this.ticksInAction = 0;
		this.path = null;
		this.pathIdx = 0;
		this.pathPos = null;
		this.anchor = entity.getPos();
	}
}
