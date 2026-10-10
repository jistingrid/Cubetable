package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> everyone: time is paused by the Dungeon Master (players can not walk or touch blocks) or running again. */
public record PausePayload(boolean paused) implements CustomPayload {
	public static final Id<PausePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "time_pause"));

	public static final PacketCodec<RegistryByteBuf, PausePayload> CODEC = new PacketCodec<>() {
		@Override
		public PausePayload decode(RegistryByteBuf buf) {
			return new PausePayload(buf.readBoolean());
		}

		@Override
		public void encode(RegistryByteBuf buf, PausePayload p) {
			buf.writeBoolean(p.paused);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
