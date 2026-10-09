package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: which character is your active actor ("" = none). */
public record ActiveActorStatePayload(String id) implements CustomPayload {
	public static final Id<ActiveActorStatePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "active_actor_state"));

	public static final PacketCodec<RegistryByteBuf, ActiveActorStatePayload> CODEC = new PacketCodec<>() {
		@Override
		public ActiveActorStatePayload decode(RegistryByteBuf buf) {
			return new ActiveActorStatePayload(buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, ActiveActorStatePayload p) {
			buf.writeString(p.id, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
