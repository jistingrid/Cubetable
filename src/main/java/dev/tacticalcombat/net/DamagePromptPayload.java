package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client: "{target} takes {amount} damage" for the owner of the target to settle (full, half, heal or a
 * custom amount). With {@code close} set the prompt is gone (someone else answered it).
 */
public record DamagePromptPayload(long id, boolean close, String target, String attacker, String label, int amount)
		implements CustomPayload {
	public static final Id<DamagePromptPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "damage_prompt"));

	public static final PacketCodec<RegistryByteBuf, DamagePromptPayload> CODEC = new PacketCodec<>() {
		@Override
		public DamagePromptPayload decode(RegistryByteBuf buf) {
			return new DamagePromptPayload(buf.readLong(), buf.readBoolean(), buf.readString(64), buf.readString(64),
					buf.readString(64), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, DamagePromptPayload p) {
			buf.writeLong(p.id);
			buf.writeBoolean(p.close);
			buf.writeString(p.target, 64);
			buf.writeString(p.attacker, 64);
			buf.writeString(p.label, 64);
			buf.writeVarInt(p.amount);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
