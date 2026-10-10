package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Dungeon Master: the entity id of the Actor body they are possessing, or -1 when they are not. */
public record PossessPayload(int bodyId) implements CustomPayload {
	public static final Id<PossessPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "possess"));

	public static final PacketCodec<RegistryByteBuf, PossessPayload> CODEC = new PacketCodec<>() {
		@Override
		public PossessPayload decode(RegistryByteBuf buf) {
			return new PossessPayload(buf.readVarInt() - 1);
		}

		@Override
		public void encode(RegistryByteBuf buf, PossessPayload p) {
			buf.writeVarInt(p.bodyId + 1);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
