package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DmStatePayload;

import java.util.List;

/** What the server last told this Dungeon Master: the tool switches and the players online. */
public final class DmState {
	public static boolean autoMovement = false;
	/** Entity id of the Actor body this Dungeon Master possesses, or -1. */
	public static int possessed = -1;
	/** The game is paused by the Dungeon Master (everyone gets this, not only DMs). */
	public static boolean paused;

	/** True when this client's player is stopped by the pause (a Dungeon Master never is). */
	public static boolean holdsPlayer() {
		return (paused && !ServerCharacters.isDm()) || DownedCard.selfDown();
	}
	public static List<DmStatePayload.Who> players = List.of();
	public static List<DmStatePayload.ActorInfo> actors = List.of();
	public static List<String> folders = List.of();
	/** Everyone who can be in a fight, ticked or not. */
	public static List<DmStatePayload.Member> roster = List.of();
	/** Entity ids of the Dungeon Masters who are invisible to the players. */
	public static List<Integer> invisibleDms = List.of();

	/** This Dungeon Master has hidden themselves from the players. */
	public static boolean selfInvisible() {
		net.minecraft.client.network.ClientPlayerEntity p = net.minecraft.client.MinecraftClient.getInstance().player;
		return p != null && invisibleDms.contains(p.getId());
	}
	/** Formats the server has characters for but no game pack. */
	public static List<String> missingFormats = List.of();

	private DmState() {}

	public static void receive(DmStatePayload p) {
		autoMovement = p.autoMovement();
		players = p.players();
		actors = p.actors();
		folders = p.folders();
		roster = p.roster();
		invisibleDms = p.invisibleDms();
		missingFormats = p.missingFormats();
		FormatUploader.offer(p.missingFormats());
	}

	/** True when the Dungeon Master walks and acts for the Actor with this entity id (its own switch). */
	public static boolean controls(int entityId) {
		for (DmStatePayload.ActorInfo a : actors) if (a.entityId() == entityId) return a.dmControl();
		return false;
	}

	public static void clear() {
		autoMovement = false;
		possessed = -1;
		paused = false;
		players = List.of();
		actors = List.of();
		folders = List.of();
		roster = List.of();
		invisibleDms = List.of();
		missingFormats = List.of();
		FormatUploader.reset();
	}
}
