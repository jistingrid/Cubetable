package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server: an answer to an attack-of-opportunity prompt. op 0: disengage first, by the option {@code arg}, then
 * move; 1: move anyway; 2: stay where you are; 3: make the opportunity attack; 4: skip it, or finish it.
 */
public record OpportunityPayload(int op, String arg) implements CustomPayload {
	public static final int DISENGAGE = 0, PROCEED = 1, CANCEL = 2, ATTACK = 3, DONE = 4;

	public static final Id<OpportunityPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "opportunity"));

	public static final PacketCodec<RegistryByteBuf, OpportunityPayload> CODEC = new PacketCodec<>() {
		@Override
		public OpportunityPayload decode(RegistryByteBuf buf) {
			return new OpportunityPayload(buf.readVarInt(), buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, OpportunityPayload p) {
			buf.writeVarInt(p.op);
			buf.writeString(p.arg, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
