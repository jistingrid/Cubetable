package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server: "use this feature" (a sheet entry that is not a roll). cost: what it costs, such as "bonus:1";
 * rules: the entry's rules text; ref: "collection:index" of the entry on the user's sheet.
 */
public record ActivatePayload(String label, String cost, String rules, String ref) implements CustomPayload {
	public static final Id<ActivatePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "activate"));

	public static final PacketCodec<RegistryByteBuf, ActivatePayload> CODEC = new PacketCodec<>() {
		@Override
		public ActivatePayload decode(RegistryByteBuf buf) {
			return new ActivatePayload(buf.readString(64), buf.readString(96), buf.readString(1000), buf.readString(40));
		}

		@Override
		public void encode(RegistryByteBuf buf, ActivatePayload p) {
			buf.writeString(p.label, 64);
			buf.writeString(p.cost, 96);
			buf.writeString(p.rules, 1000);
			buf.writeString(p.ref, 40);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
