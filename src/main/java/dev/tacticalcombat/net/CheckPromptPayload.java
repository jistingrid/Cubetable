package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client: "{requester} asks {target} for a {label}" for the owner of the target to roll. {@code dc} is the
 * number to beat, or -1 when it is not shown. With {@code close} set the request is gone (someone else answered it).
 */
public record CheckPromptPayload(long id, boolean close, String target, String requester, String label, int dc)
		implements CustomPayload {
	public static final Id<CheckPromptPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "check_prompt"));

	public static final PacketCodec<RegistryByteBuf, CheckPromptPayload> CODEC = new PacketCodec<>() {
		@Override
		public CheckPromptPayload decode(RegistryByteBuf buf) {
			return new CheckPromptPayload(buf.readLong(), buf.readBoolean(), buf.readString(64), buf.readString(64),
					buf.readString(64), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, CheckPromptPayload p) {
			buf.writeLong(p.id);
			buf.writeBoolean(p.close);
			buf.writeString(p.target, 64);
			buf.writeString(p.requester, 64);
			buf.writeString(p.label, 64);
			buf.writeVarInt(p.dc);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
