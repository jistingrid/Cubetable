package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> clients: a character was deleted. */
public record CharacterRemovePayload(String id) implements CustomPayload {
	public static final Id<CharacterRemovePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "character_remove"));

	public static final PacketCodec<RegistryByteBuf, CharacterRemovePayload> CODEC = new PacketCodec<>() {
		@Override
		public CharacterRemovePayload decode(RegistryByteBuf buf) {
			return new CharacterRemovePayload(buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, CharacterRemovePayload p) {
			buf.writeString(p.id, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
