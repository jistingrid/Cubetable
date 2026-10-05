package dev.tacticalcombat.combat;

/**
 * Tunable constants for the turn based combat. Distances are in blocks / grid squares, times in ticks.
 * (A later phase can turn these into a real config file.)
 */
public final class CombatConfig {
	private CombatConfig() {}

	/** A hostile that targets a player within this range starts combat. */
	public static final double DETECT_RANGE = 16.0;
	/** Hostiles and players within this range of the trigger join the fight. */
	public static final double JOIN_RANGE = 24.0;
	/** Combat ends if every enemy is farther than this from every player. */
	public static final double LEAVE_RANGE = 48.0;

	/** Grid squares a player may walk per turn (leftover movement can be spent in several moves). */
	public static final double PLAYER_MOVEMENT = 8.0;
	/** Horizontal blocks a hostile mob may walk per turn (mobs still walk freely, not square by square). */
	public static final double MOB_MOVEMENT = 6.0;

	/** Blocks per tick the server slides a player along a chosen path (0.4 = 8 squares per second). */
	public static final double MOVE_SPEED = 0.4;
	/** Enemies farther than this from the active player are ignored when drawing the red threat area. */
	public static final double THREAT_SCAN_RANGE = 28.0;
	/** At most this many enemies contribute to the threat area (keeps the search cheap). */
	public static final int THREAT_MAX_ENEMIES = 12;

	/** Hard cap on a mob's turn length. */
	public static final int MOB_TURN_MAX_TICKS = 100;
	/** Ticks a mob keeps its turn after it used its action. */
	public static final int MOB_AFTER_ACTION_TICKS = 15;
	/** Ticks a mob keeps its turn after running out of movement. */
	public static final int MOB_AFTER_MOVE_TICKS = 20;

	/** Player turn time limit in ticks. 0 = unlimited. */
	public static final int PLAYER_TURN_TICKS = 0;
}
