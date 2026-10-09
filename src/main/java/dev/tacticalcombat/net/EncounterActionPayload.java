package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server, from the encounter window.
 * op: 0 roll my initiative, 1 roll every enemy (DM), 2 set an initiative value (DM), 3 move one place in the
 * order, sign of value = direction (DM), 4 start the turns (DM), 5 sort the order by initiative again (DM).
 */
public record EncounterActionPayload(int op, int entityId, int value) implements CustomPayload {
	public static final Id<EncounterActionPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "encounter_action"));

	public static final PacketCodec<RegistryByteBuf, EncounterActionPayload> CODEC = new PacketCodec<>() {
		@Override
		public EncounterActionPayload decode(RegistryByteBuf buf) {
			return new EncounterActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, EncounterActionPayload p) {
			buf.writeVarInt(p.op);
			buf.writeVarInt(p.entityId);
			buf.writeInt(p.value);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
