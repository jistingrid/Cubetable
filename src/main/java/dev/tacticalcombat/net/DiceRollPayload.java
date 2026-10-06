package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.dice.DiceType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: a die roll to animate on screen, then print in chat. The result is decided by the server. */
public record DiceRollPayload(String roller, DiceType type, int modifier, int[] results) implements CustomPayload {
	public static final Id<DiceRollPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dice_roll"));

	public static final PacketCodec<RegistryByteBuf, DiceRollPayload> CODEC = new PacketCodec<>() {
		@Override
		public DiceRollPayload decode(RegistryByteBuf buf) {
			String roller = buf.readString(64);
			DiceType type = DiceType.values()[buf.readVarInt()];
			int modifier = buf.readVarInt();
			int n = buf.readVarInt();
			int[] results = new int[n];
			for (int i = 0; i < n; i++) {
				results[i] = buf.readVarInt();
			}
			return new DiceRollPayload(roller, type, modifier, results);
		}

		@Override
		public void encode(RegistryByteBuf buf, DiceRollPayload p) {
			buf.writeString(p.roller, 64);
			buf.writeVarInt(p.type.ordinal());
			buf.writeVarInt(p.modifier);
			buf.writeVarInt(p.results.length);
			for (int r : p.results) {
				buf.writeVarInt(r);
			}
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
