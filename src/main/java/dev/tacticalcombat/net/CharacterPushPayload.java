package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: "this is the current state of my character" (the sheet JSON, gzipped; the packet limit is small). */
public record CharacterPushPayload(String id, byte[] data) implements CustomPayload {
	public static final Id<CharacterPushPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "character_push"));

	public static final PacketCodec<RegistryByteBuf, CharacterPushPayload> CODEC = new PacketCodec<>() {
		@Override
		public CharacterPushPayload decode(RegistryByteBuf buf) {
			return new CharacterPushPayload(buf.readString(64), buf.readByteArray(32000));
		}

		@Override
		public void encode(RegistryByteBuf buf, CharacterPushPayload p) {
			buf.writeString(p.id, 64);
			buf.writeByteArray(p.data);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
