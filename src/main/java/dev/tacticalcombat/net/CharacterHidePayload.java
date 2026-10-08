package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> one client: this character is no longer shared with you (it still exists). */
public record CharacterHidePayload(String id) implements CustomPayload {
	public static final Id<CharacterHidePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "character_hide"));

	public static final PacketCodec<RegistryByteBuf, CharacterHidePayload> CODEC = new PacketCodec<>() {
		@Override
		public CharacterHidePayload decode(RegistryByteBuf buf) {
			return new CharacterHidePayload(buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, CharacterHidePayload p) {
			buf.writeString(p.id, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
