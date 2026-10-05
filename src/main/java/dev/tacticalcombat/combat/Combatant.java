package dev.tacticalcombat.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/** One participant of a {@link Combat}: a player or a hostile mob, plus its per-turn resources. */
public final class Combatant {
	public final LivingEntity entity;
	public int initiative;

	/** Where the combatant is frozen (waiting) or the last accepted position (during its turn). */
	public Vec3d anchor;

	public double moveBudget;
	public double moveUsed;
	public boolean actionUsed;
	public boolean bonusActionUsed;

	public int ticksInTurn;
	public int ticksSinceActionUsed;
	public int ticksSinceMoveExhausted;
	public boolean moveExhausted;

	public Combatant(LivingEntity entity, int initiative) {
		this.entity = entity;
		this.initiative = initiative;
		this.anchor = entity.getPos();
	}

	public boolean isPlayer() {
		return entity instanceof ServerPlayerEntity;
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
		this.anchor = entity.getPos();
	}
}
