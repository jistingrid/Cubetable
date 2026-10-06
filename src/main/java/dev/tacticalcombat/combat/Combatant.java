package dev.tacticalcombat.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** One participant of a {@link Combat}: a player or a hostile mob, plus its per-turn resources. */
public final class Combatant {
	public final LivingEntity entity;
	public int initiative;

	/** Where the combatant is held (waiting / standing still) or the last accepted position (mob turn). */
	public Vec3d anchor;

	/** Squares (players) or blocks (mobs) of movement. */
	public double moveBudget;
	public double moveUsed;
	public boolean actionUsed;
	public boolean bonusActionUsed;

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

	public void resetTurnResources(double budget) {
		this.moveBudget = budget;
		this.moveUsed = 0;
		this.actionUsed = false;
		this.bonusActionUsed = false;
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
