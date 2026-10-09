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

	/** Same as {@link #roll} and returns the total (kept dice plus modifier) so the server can use it. */
	public static int rollTotal(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode) {
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

		DiceRollPayload payload = new DiceRollPayload(player.getGameProfile().getName(), label, type, modifier, keep, results);
		for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
			ServerPlayNetworking.send(p, payload);
		}
		int total;
		if (keep == 1) total = Math.max(results[0], results[1]);
		else if (keep == 2) total = Math.min(results[0], results[1]);
		else {
			total = 0;
			for (int r : results) total += r;
		}
		return total + modifier;
	}
}
