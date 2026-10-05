package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Client -> server: "walk me to this square". The server re-validates it against its own reachable set. */
public record MoveRequestPayload(BlockPos target) implements CustomPayload {
	public static final Id<MoveRequestPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "move_request"));

	public static final PacketCodec<RegistryByteBuf, MoveRequestPayload> CODEC = new PacketCodec<>() {
		@Override
		public MoveRequestPayload decode(RegistryByteBuf buf) {
			return new MoveRequestPayload(buf.readBlockPos());
		}

		@Override
		public void encode(RegistryByteBuf buf, MoveRequestPayload p) {
			buf.writeBlockPos(p.target);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
