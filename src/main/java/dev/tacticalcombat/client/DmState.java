package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DmStatePayload;

import java.util.List;

/** What the server last told this Dungeon Master: the tool switches and the players online. */
public final class DmState {
	public static boolean autoMovement = false;
	/** Entity id of the Actor body this Dungeon Master possesses, or -1. */
	public static int possessed = -1;
	public static List<DmStatePayload.Who> players = List.of();
	public static List<DmStatePayload.ActorInfo> actors = List.of();

	private DmState() {}

	public static void receive(DmStatePayload p) {
		autoMovement = p.autoMovement();
		players = p.players();
		actors = p.actors();
	}

	/** True when the Dungeon Master walks and acts for the Actor with this entity id (its own switch). */
	public static boolean controls(int entityId) {
		for (DmStatePayload.ActorInfo a : actors) if (a.entityId() == entityId) return a.dmControl();
		return false;
	}

	public static void clear() {
		autoMovement = false;
		possessed = -1;
		players = List.of();
		actors = List.of();
	}
}
