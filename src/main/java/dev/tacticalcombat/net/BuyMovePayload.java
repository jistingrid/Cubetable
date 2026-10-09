package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: spend resources on one of the pack's movement options (such as Dash). */
public record BuyMovePayload(String id) implements CustomPayload {
	public static final Id<BuyMovePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "buy_move"));

	public static final PacketCodec<RegistryByteBuf, BuyMovePayload> CODEC = new PacketCodec<>() {
		@Override
		public BuyMovePayload decode(RegistryByteBuf buf) {
			return new BuyMovePayload(buf.readString(32));
		}

		@Override
		public void encode(RegistryByteBuf buf, BuyMovePayload p) {
			buf.writeString(p.id, 32);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
