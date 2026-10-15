package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> everyone: which time of day and weather the Dungeon Master holds (0 = natural, nothing held). */
public record AmbiencePayload(int time, int weather) implements CustomPayload {
	public static final Id<AmbiencePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "ambience"));

	public static final PacketCodec<RegistryByteBuf, AmbiencePayload> CODEC = new PacketCodec<>() {
		@Override
		public AmbiencePayload decode(RegistryByteBuf buf) {
			return new AmbiencePayload(buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, AmbiencePayload p) {
			buf.writeVarInt(p.time);
			buf.writeVarInt(p.weather);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
