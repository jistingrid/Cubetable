package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> clients: a character was added or changed. byUuid is who changed it (empty when the server did). */
public record CharacterUpdatePayload(String id, String ownerUuid, String ownerName, String byUuid, long version, byte[] data) implements CustomPayload {
	public static final Id<CharacterUpdatePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "character_update"));

	public static final PacketCodec<RegistryByteBuf, CharacterUpdatePayload> CODEC = new PacketCodec<>() {
		@Override
		public CharacterUpdatePayload decode(RegistryByteBuf buf) {
			return new CharacterUpdatePayload(buf.readString(64), buf.readString(40), buf.readString(32), buf.readString(40), buf.readLong(), buf.readByteArray(900000));
		}

		@Override
		public void encode(RegistryByteBuf buf, CharacterUpdatePayload p) {
			buf.writeString(p.id, 64);
			buf.writeString(p.ownerUuid, 40);
			buf.writeString(p.ownerName, 32);
			buf.writeString(p.byUuid, 40);
			buf.writeLong(p.version);
			buf.writeByteArray(p.data);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
