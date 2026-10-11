package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Dungeon Master -> server: a game pack's sheet format (gzipped JSON). A dedicated server reads hit points and combat
 * rules from its own copy of the packs; this lets a DM who has the pack install it there without touching the server's files.
 */
public record FormatUploadPayload(String id, byte[] data) implements CustomPayload {
	public static final Id<FormatUploadPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "format_upload"));

	public static final PacketCodec<RegistryByteBuf, FormatUploadPayload> CODEC = new PacketCodec<>() {
		@Override
		public FormatUploadPayload decode(RegistryByteBuf buf) {
			return new FormatUploadPayload(buf.readString(64), buf.readByteArray(30000));
		}

		@Override
		public void encode(RegistryByteBuf buf, FormatUploadPayload p) {
			buf.writeString(p.id, 64);
			buf.writeByteArray(p.data);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
