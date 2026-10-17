package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: the open rest where hit dice can be spent (or, with {@code close}, nothing is open any more). */
public record RestPromptPayload(boolean close, String title, int left, int max, int sides, String health, String lastRoll) implements CustomPayload {
	public static final Id<RestPromptPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "rest_prompt"));

	public static RestPromptPayload closed() {
		return new RestPromptPayload(true, "", 0, 0, 0, "", "");
	}

	public static final PacketCodec<RegistryByteBuf, RestPromptPayload> CODEC = new PacketCodec<>() {
		@Override
		public RestPromptPayload decode(RegistryByteBuf buf) {
			return new RestPromptPayload(buf.readBoolean(), buf.readString(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readString(64), buf.readString(96));
		}

		@Override
		public void encode(RegistryByteBuf buf, RestPromptPayload p) {
			buf.writeBoolean(p.close);
			buf.writeString(p.title, 64);
			buf.writeVarInt(p.left);
			buf.writeVarInt(p.max);
			buf.writeVarInt(p.sides);
			buf.writeString(p.health, 64);
			buf.writeString(p.lastRoll, 96);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
