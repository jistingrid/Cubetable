package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server, from the downed card. op: 0 roll the death save, 1 stabilize, 2 kill, 3 revive with 1 point (the last three are DM only). */
public record DownedActionPayload(int entityId, int op) implements CustomPayload {
	public static final Id<DownedActionPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "downed_action"));

	public static final PacketCodec<RegistryByteBuf, DownedActionPayload> CODEC = new PacketCodec<>() {
		@Override
		public DownedActionPayload decode(RegistryByteBuf buf) {
			return new DownedActionPayload(buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, DownedActionPayload p) {
			buf.writeVarInt(p.entityId);
			buf.writeVarInt(p.op);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
