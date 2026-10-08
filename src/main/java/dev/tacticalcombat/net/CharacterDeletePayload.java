package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: remove a character (its owner or a DM only). */
public record CharacterDeletePayload(String id) implements CustomPayload {
	public static final Id<CharacterDeletePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "character_delete"));

	public static final PacketCodec<RegistryByteBuf, CharacterDeletePayload> CODEC = new PacketCodec<>() {
		@Override
		public CharacterDeletePayload decode(RegistryByteBuf buf) {
			return new CharacterDeletePayload(buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, CharacterDeletePayload p) {
			buf.writeString(p.id, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
