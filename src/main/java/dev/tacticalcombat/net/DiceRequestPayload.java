package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.dice.DiceType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server: "roll this for me" (sent by the character sheet). mode: 0 normal, 1 advantage, 2 disadvantage.
 * kind: 0 a plain roll, 1 an attack roll against the player's target, 2 a damage roll for the player's target.
 */
public record DiceRequestPayload(String label, DiceType type, int count, int modifier, int mode, int kind) implements CustomPayload {
	public static final Id<DiceRequestPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dice_request"));

	public static final PacketCodec<RegistryByteBuf, DiceRequestPayload> CODEC = new PacketCodec<>() {
		@Override
		public DiceRequestPayload decode(RegistryByteBuf buf) {
			String label = buf.readString(64);
			int typeIndex = buf.readVarInt();
			DiceType type = DiceType.values()[Math.floorMod(typeIndex, DiceType.values().length)];
			return new DiceRequestPayload(label, type, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, DiceRequestPayload p) {
			buf.writeString(p.label, 64);
			buf.writeVarInt(p.type.ordinal());
			buf.writeVarInt(p.count);
			buf.writeVarInt(p.modifier);
			buf.writeVarInt(p.mode);
			buf.writeVarInt(p.kind);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
