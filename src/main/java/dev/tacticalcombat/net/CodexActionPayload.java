package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Dungeon Master -> server, from the Codex tab. {@code key} names the entry (see {@code CodexKeys}); for {@link #ADD}
 * {@code data} is the entry's sheet (gzipped JSON), for {@link #REMOVE} it is empty.
 */
public record CodexActionPayload(int op, String key, byte[] data) implements CustomPayload {
	/** Put the entry into the game: the server keeps it as a hidden sheet the Actors tab can use (adding it again updates it). */
	public static final int ADD = 0;
	/** Take the entry out of the game again (refused while an Actor shares its sheet). */
	public static final int REMOVE = 1;

	public static final Id<CodexActionPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "codex_action"));

	public static final PacketCodec<RegistryByteBuf, CodexActionPayload> CODEC = new PacketCodec<>() {
		@Override
		public CodexActionPayload decode(RegistryByteBuf buf) {
			return new CodexActionPayload(buf.readVarInt(), buf.readString(128), buf.readByteArray(32000));
		}

		@Override
		public void encode(RegistryByteBuf buf, CodexActionPayload p) {
			buf.writeVarInt(p.op);
			buf.writeString(p.key, 128);
			buf.writeByteArray(p.data);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
