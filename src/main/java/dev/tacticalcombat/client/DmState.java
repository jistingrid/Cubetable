package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DmStatePayload;

import java.util.List;

/** What the server last told this Dungeon Master: the tool switches and the players online. */
public final class DmState {
	public static boolean autoMovement = true;
	public static List<DmStatePayload.Who> players = List.of();

	private DmState() {}

	public static void receive(DmStatePayload p) {
		autoMovement = p.autoMovement();
		players = p.players();
	}

	public static void clear() {
		autoMovement = true;
		players = List.of();
	}
}
