package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> server: the creatures (entity ids) the next rolls of this player are aimed at; empty clears them. {@code separate}:
 * each pick gets its own attack and damage roll (darts, rays) instead of one shared roll.
 */
public record AimPayload(List<Integer> ids, boolean separate) implements CustomPayload {
	public static final Id<AimPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "aim"));

	public static final PacketCodec<RegistryByteBuf, AimPayload> CODEC = new PacketCodec<>() {
		@Override
		public AimPayload decode(RegistryByteBuf buf) {
			int n = Math.min(buf.readVarInt(), 64);
			List<Integer> ids = new ArrayList<>(n);
			for (int i = 0; i < n; i++) ids.add(buf.readVarInt());
			return new AimPayload(ids, buf.readBoolean());
		}

		@Override
		public void encode(RegistryByteBuf buf, AimPayload p) {
			buf.writeVarInt(p.ids.size());
			for (int id : p.ids) buf.writeVarInt(id);
			buf.writeBoolean(p.separate);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
