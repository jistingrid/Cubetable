package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Client -> server: "walk me to this square". The server re-validates it against its own reachable set. {@code confirm}:
 * the player has already agreed to the attacks of opportunity the walk provokes (otherwise the server asks first).
 */
public record MoveRequestPayload(BlockPos target, boolean confirm) implements CustomPayload {
	public static final Id<MoveRequestPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "move_request"));

	public static final PacketCodec<RegistryByteBuf, MoveRequestPayload> CODEC = new PacketCodec<>() {
		@Override
		public MoveRequestPayload decode(RegistryByteBuf buf) {
			return new MoveRequestPayload(buf.readBlockPos(), buf.readBoolean());
		}

		@Override
		public void encode(RegistryByteBuf buf, MoveRequestPayload p) {
			buf.writeBlockPos(p.target);
			buf.writeBoolean(p.confirm);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
