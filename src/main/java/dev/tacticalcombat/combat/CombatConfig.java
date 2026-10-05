package dev.tacticalcombat.combat;

/**
 * Tunable constants for the turn based combat. Everything is in blocks / ticks.
 * (Phase 2 can turn these into a real config file.)
 */
public final class CombatConfig {
	private CombatConfig() {}

	/** A hostile that targets a player within this range starts combat. */
	public static final double DETECT_RANGE = 16.0;
	/** Hostiles and players within this range of the trigger join the fight. */
	public static final double JOIN_RANGE = 24.0;
	/** Combat ends if every enemy is farther than this from every player. */
	public static final double LEAVE_RANGE = 48.0;

	/** Horizontal blocks a player may walk per turn. */
	public static final double PLAYER_MOVEMENT = 8.0;
	/** Horizontal blocks a hostile mob may walk per turn. */
	public static final double MOB_MOVEMENT = 6.0;

	/** Hard cap on a mob's turn length. */
	public static final int MOB_TURN_MAX_TICKS = 100;
	/** Ticks a mob keeps its turn after it used its action. */
	public static final int MOB_AFTER_ACTION_TICKS = 15;
	/** Ticks a mob keeps its turn after running out of movement. */
	public static final int MOB_AFTER_MOVE_TICKS = 20;

	/** Player turn time limit in ticks. 0 = unlimited. */
	public static final int PLAYER_TURN_TICKS = 0;
}
