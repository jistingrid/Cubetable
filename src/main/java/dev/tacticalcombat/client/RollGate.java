package dev.tacticalcombat.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps a roll from being spammed: while a roll is on its way (sent, or its dice still tumbling) the same roll can not
 * be started again. The roll is known by the label it was sent with; it is free again when the dice animation of that
 * roll has finished, or after a timeout in case the server never answered (not your turn, a refused request).
 */
public final class RollGate {
	private static final long ROLL_TTL_MS = 10_000;
	private static final Map<String, Long> PENDING = new HashMap<>();

	private RollGate() {}

	/** Starts the roll: false when the same one is still running. {@code ttlMs}: how long it is held with no animation. */
	public static boolean tryStart(String key, long ttlMs) {
		expire();
		if (PENDING.containsKey(key)) return false;
		PENDING.put(key, System.currentTimeMillis() + ttlMs);
		return true;
	}

	public static boolean tryStart(String key) {
		return tryStart(key, ROLL_TTL_MS);
	}

	/** A roll that did not go out after all: free it at once. */
	public static void release(String key) {
		PENDING.remove(key);
	}

	/** True when a roll whose label starts with this one is running (to check before something is spent for it). */
	public static boolean busyPrefix(String prefix) {
		expire();
		for (String k : PENDING.keySet()) if (k.startsWith(prefix)) return true;
		return false;
	}

	/** The dice animation of this roll (as the server labelled it) has finished: the same roll may start again. */
	public static void finished(String label) {
		if (label == null) return;
		PENDING.keySet().removeIf(k -> label.equals(k) || label.startsWith(k) || k.startsWith(label));
	}

	public static void clear() {
		PENDING.clear();
	}

	private static void expire() {
		long now = System.currentTimeMillis();
		for (Iterator<Long> it = PENDING.values().iterator(); it.hasNext();) if (it.next() < now) it.remove();
	}
}
