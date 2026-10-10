package dev.tacticalcombat.dice;

import dev.tacticalcombat.net.DiceRollPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Random;

/** Server side of every dice roll: the server picks the numbers, then every player plays the same animation. */
public final class DiceService {
	private static final Random RANDOM = new Random();

	private DiceService() {}

	/** mode: 0 normal, 1 advantage, 2 disadvantage (only meaningful for a single d20). */
	public static void roll(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode) {
		rollTotal(player, label, type, count, modifier, mode);
	}

	/** A line printed under the roll in chat. outcome: 0 neutral, 1 hit, 2 miss. */
	public record Verdict(int outcome, String text) {
		public static final Verdict NONE = new Verdict(0, "");
	}

	/** Same as {@link #roll} and returns the total (kept dice plus modifier) so the server can use it. */
	public static int rollTotal(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode) {
		return rollTotal(player, label, type, count, modifier, mode, null);
	}

	/** As above; {@code verdict} gets the total and may add a line under the roll (a hit or a miss). */
	public static int rollTotal(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode,
								java.util.function.IntFunction<Verdict> verdict) {
		return rollTotal(player, null, label, type, count, modifier, mode, verdict);
	}

	/** As above, shown as rolled by {@code roller} (a creature a Dungeon Master rolls for); null = the player. */
	public static int rollTotal(ServerPlayerEntity player, String roller, String label, DiceType type, int count, int modifier,
								int mode, java.util.function.IntFunction<Verdict> verdict) {
		count = Math.max(1, Math.min(DiceSpec.MAX_COUNT, count));
		modifier = Math.max(-999, Math.min(999, modifier));
		int keep = 0;
		if (type == DiceType.D20 && count == 1 && (mode == 1 || mode == 2)) {
			count = 2;
			keep = mode;
		}
		int[] results = new int[count];
		for (int i = 0; i < count; i++) {
			results[i] = type.roll(RANDOM);
		}
		if (label == null) label = "";
		if (label.length() > 48) label = label.substring(0, 48);

		int total;
		if (keep == 1) total = Math.max(results[0], results[1]);
		else if (keep == 2) total = Math.min(results[0], results[1]);
		else {
			total = 0;
			for (int r : results) total += r;
		}
		total += modifier;

		Verdict v = verdict == null ? Verdict.NONE : verdict.apply(total);
		DiceRollPayload payload = new DiceRollPayload(roller == null || roller.isBlank() ? player.getGameProfile().getName() : roller, label, type, modifier, keep, results,
				v.outcome(), v.text());
		for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(p, payload);
		}
		return total;
	}
}
