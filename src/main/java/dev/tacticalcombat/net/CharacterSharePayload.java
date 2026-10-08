package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client (a Dungeon Master) -> server: who may see this character. "" = nobody, "*" = everyone, else player uuids. */
public record CharacterSharePayload(String id, String share) implements CustomPayload {
	public static final Id<CharacterSharePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "character_share"));

	public static final PacketCodec<RegistryByteBuf, CharacterSharePayload> CODEC = new PacketCodec<>() {
		@Override
		public CharacterSharePayload decode(RegistryByteBuf buf) {
			return new CharacterSharePayload(buf.readString(64), buf.readString(2000));
		}

		@Override
		public void encode(RegistryByteBuf buf, CharacterSharePayload p) {
			buf.writeString(p.id, 64);
			buf.writeString(p.share, 2000);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
