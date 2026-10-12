package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> nearby players: a number or word floats up from a creature. {@code kind}: 0 damage, 1 healing, 2 resource
 * (coloured by {@code color}), 3 status gained, 4 status gone. {@code pct} is the share of the creature's maximum
 * health the damage was (0 - 1), which sets how dark the red is.
 */
public record FloatTextPayload(int entityId, int kind, String text, float pct, int color) implements CustomPayload {
	public static final Id<FloatTextPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "float_text"));

	public static final PacketCodec<RegistryByteBuf, FloatTextPayload> CODEC = new PacketCodec<>() {
		@Override
		public FloatTextPayload decode(RegistryByteBuf buf) {
			return new FloatTextPayload(buf.readVarInt(), buf.readByte(), buf.readString(40), buf.readFloat(), buf.readInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, FloatTextPayload p) {
			buf.writeVarInt(p.entityId);
			buf.writeByte(p.kind);
			buf.writeString(p.text.length() > 40 ? p.text.substring(0, 40) : p.text);
			buf.writeFloat(p.pct);
			buf.writeInt(p.color);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
