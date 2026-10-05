package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: "I am done with my turn". */
public record EndTurnPayload() implements CustomPayload {
	public static final EndTurnPayload INSTANCE = new EndTurnPayload();

	public static final Id<EndTurnPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "end_turn"));

	public static final PacketCodec<RegistryByteBuf, EndTurnPayload> CODEC = PacketCodec.unit(INSTANCE);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
