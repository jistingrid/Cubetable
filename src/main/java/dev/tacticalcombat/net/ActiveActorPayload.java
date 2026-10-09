package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: make this character the one my model stands for ("" = none). */
public record ActiveActorPayload(String id) implements CustomPayload {
	public static final Id<ActiveActorPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "active_actor"));

	public static final PacketCodec<RegistryByteBuf, ActiveActorPayload> CODEC = new PacketCodec<>() {
		@Override
		public ActiveActorPayload decode(RegistryByteBuf buf) {
			return new ActiveActorPayload(buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, ActiveActorPayload p) {
			buf.writeString(p.id, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
