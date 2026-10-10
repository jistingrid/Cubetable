package dev.tacticalcombat.actor;

import java.util.UUID;

/**
 * One Actor: a named creature or person of the world that the Dungeon Master places like a token. It has a sheet
 * (a server character), a model (what its body looks like) and, once placed, a body entity in the world.
 */
public final class ActorRecord {
	public static final int HOSTILE = 0;
	public static final int NEUTRAL = 1;
	public static final int FRIENDLY = 2;

	public final String id;
	public String name;
	/** Server id of the character that gives this Actor its numbers. */
	public String sheetId = "";
	/** True when the Actor has its own copy of the sheet (its own hit points); false when it shares a character. */
	public boolean ownsSheet;
	/** "mob" (any Minecraft creature), "skin" (a player model with a skin), "item" or "block" (a floating prop). */
	public String kind = "mob";
	/** mob: entity id; skin: player name or texture id; item / block: its registry id. */
	public String value = "minecraft:villager";
	public int disposition = HOSTILE;
	/** The Dungeon Master walks and acts for this Actor in a fight, whatever the Auto movement switch says. */
	public boolean dmControl;
	/** The body in the world, null while the Actor is only a record. */
	public UUID entityUuid;

	public ActorRecord(String id, String name) {
		this.id = id;
		this.name = name;
	}

	public String model() {
		return kind + ":" + value;
	}
}
