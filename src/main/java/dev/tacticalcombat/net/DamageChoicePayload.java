package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server: how a damage prompt is settled. mode: 0 full damage, 1 half damage, 2 heal the whole amount,
 * 3 custom damage, 4 custom heal ({@code amount} is the custom number).
 */
public record DamageChoicePayload(long id, int mode, int amount) implements CustomPayload {
	public static final Id<DamageChoicePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "damage_choice"));

	public static final PacketCodec<RegistryByteBuf, DamageChoicePayload> CODEC = new PacketCodec<>() {
		@Override
		public DamageChoicePayload decode(RegistryByteBuf buf) {
			return new DamageChoicePayload(buf.readLong(), buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, DamageChoicePayload p) {
			buf.writeLong(p.id);
			buf.writeVarInt(p.mode);
			buf.writeVarInt(p.amount);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
