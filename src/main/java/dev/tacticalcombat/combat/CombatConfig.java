package dev.tacticalcombat.combat;

/**
 * Tunable constants for the turn based combat. Distances are in blocks / grid squares, times in ticks.
 * (A later phase can turn these into a real config file.)
 */
public final class CombatConfig {
	private CombatConfig() {}

	// "Vicinity" = close on the ground (horizontal distance) AND on roughly the same level (vertical limit),
	// so mobs in caves far below, or high above, never count. Leave limits are larger than join limits so
	// enemies hovering at the edge do not flip in and out of the fight.

	/** A hostile that targets a player within this horizontal range starts combat. */
	public static final double DETECT_RANGE = 16.0;
	/** Hostiles and players within this horizontal range of the trigger / party join the fight. */
	public static final double JOIN_RANGE = 16.0;
	/** ... and must also be within this many blocks above or below. */
	public static final double JOIN_VERTICAL = 6.0;
	/** An enemy farther than this horizontally from every player is removed from the fight. */
	public static final double LEAVE_RANGE = 24.0;
	/** ... or farther than this many blocks above / below every player. */
	public static final double LEAVE_VERTICAL = 10.0;

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
