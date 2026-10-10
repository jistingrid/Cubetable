package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: "I target this creature" (the entity id of a combatant). Choosing the same one again clears it. */
public record TargetPayload(int entityId) implements CustomPayload {
	public static final Id<TargetPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "target"));

	public static final PacketCodec<RegistryByteBuf, TargetPayload> CODEC = new PacketCodec<>() {
		@Override
		public TargetPayload decode(RegistryByteBuf buf) {
			return new TargetPayload(buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, TargetPayload p) {
			buf.writeVarInt(p.entityId);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
