package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: how a check request is rolled. mode: 0 normal, 1 advantage, 2 disadvantage. */
public record CheckChoicePayload(long id, int mode) implements CustomPayload {
	public static final Id<CheckChoicePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "check_choice"));

	public static final PacketCodec<RegistryByteBuf, CheckChoicePayload> CODEC = new PacketCodec<>() {
		@Override
		public CheckChoicePayload decode(RegistryByteBuf buf) {
			return new CheckChoicePayload(buf.readLong(), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, CheckChoicePayload p) {
			buf.writeLong(p.id);
			buf.writeVarInt(p.mode);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
