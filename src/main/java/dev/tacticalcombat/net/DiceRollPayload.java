package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.dice.DiceType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: a die roll to animate on screen, then print in chat. The result is decided by the server. */
/**
 * keep: 0 = every die counts, 1 = only the highest (advantage), 2 = only the lowest (disadvantage).
 * outcome / verdict: a line printed under the roll in chat ("17 vs AC 15: hits Goblin"); outcome 0 neutral, 1 hit, 2 miss.
 */
public record DiceRollPayload(String roller, String label, DiceType type, int modifier, int keep, int[] results,
							  int outcome, String verdict) implements CustomPayload {
	public static final Id<DiceRollPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dice_roll"));

	public static final PacketCodec<RegistryByteBuf, DiceRollPayload> CODEC = new PacketCodec<>() {
		@Override
		public DiceRollPayload decode(RegistryByteBuf buf) {
			String roller = buf.readString(64);
			String label = buf.readString(64);
			DiceType type = DiceType.values()[buf.readVarInt()];
			int modifier = buf.readVarInt();
			int keep = buf.readVarInt();
			int n = buf.readVarInt();
			int[] results = new int[n];
			for (int i = 0; i < n; i++) {
				results[i] = buf.readVarInt();
			}
			int outcome = buf.readVarInt();
			String verdict = buf.readString(160);
			return new DiceRollPayload(roller, label, type, modifier, keep, results, outcome, verdict);
		}

		@Override
		public void encode(RegistryByteBuf buf, DiceRollPayload p) {
			buf.writeString(p.roller, 64);
			buf.writeString(p.label, 64);
			buf.writeVarInt(p.type.ordinal());
			buf.writeVarInt(p.modifier);
			buf.writeVarInt(p.keep);
			buf.writeVarInt(p.results.length);
			for (int r : p.results) {
				buf.writeVarInt(r);
			}
			buf.writeVarInt(p.outcome);
			buf.writeString(p.verdict, 160);
		}
	};

	/** Index of the single die that counts, or -1 when every die counts. */
	public int keptIndex() {
		if (keep == 0 || results.length < 2) return -1;
		int best = 0;
		for (int i = 1; i < results.length; i++) {
			if (keep == 1 ? results[i] > results[best] : results[i] < results[best]) best = i;
		}
		return best;
	}

	public int total() {
		int kept = keptIndex();
		int sum = modifier;
		for (int i = 0; i < results.length; i++) {
			if (kept < 0 || i == kept) sum += results[i];
		}
		return sum;
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
