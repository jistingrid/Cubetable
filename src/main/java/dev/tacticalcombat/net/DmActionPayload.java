package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server, from a Dungeon Master's tools. op 0: start an encounter around the party; op 1: auto movement on (value 1) or off (0). */
public record DmActionPayload(int op, int value) implements CustomPayload {
	public static final Id<DmActionPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dm_action"));

	public static final PacketCodec<RegistryByteBuf, DmActionPayload> CODEC = new PacketCodec<>() {
		@Override
		public DmActionPayload decode(RegistryByteBuf buf) {
			return new DmActionPayload(buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, DmActionPayload p) {
			buf.writeVarInt(p.op);
			buf.writeVarInt(p.value);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
