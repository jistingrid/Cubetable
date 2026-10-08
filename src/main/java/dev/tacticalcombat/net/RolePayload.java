package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: whether you are a Dungeon Master. Also the last message of the character sync after joining. */
public record RolePayload(boolean dm) implements CustomPayload {
	public static final Id<RolePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "role"));

	public static final PacketCodec<RegistryByteBuf, RolePayload> CODEC = new PacketCodec<>() {
		@Override
		public RolePayload decode(RegistryByteBuf buf) {
			return new RolePayload(buf.readBoolean());
		}

		@Override
		public void encode(RegistryByteBuf buf, RolePayload p) {
			buf.writeBoolean(p.dm);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
