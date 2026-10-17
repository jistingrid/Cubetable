package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server, about resting. op 0 REQUEST: rest (rest type {@code arg}) on the character with server link {@code link}
 * (blank: your own Active Actor); op 1 SPEND: roll one hit die in the open rest; op 2 DONE: finish it; op 3 PARTY: a Dungeon
 * Master calls rest type {@code arg} for every player.
 */
public record RestPayload(int op, String arg, String link) implements CustomPayload {
	public static final int REQUEST = 0, SPEND = 1, DONE = 2, PARTY = 3;

	public RestPayload(int op, String arg) {
		this(op, arg, "");
	}

	public static final Id<RestPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "rest"));

	public static final PacketCodec<RegistryByteBuf, RestPayload> CODEC = new PacketCodec<>() {
		@Override
		public RestPayload decode(RegistryByteBuf buf) {
			return new RestPayload(buf.readVarInt(), buf.readString(48), buf.readString(64));
		}

		@Override
		public void encode(RegistryByteBuf buf, RestPayload p) {
			buf.writeVarInt(p.op);
			buf.writeString(p.arg, 48);
			buf.writeString(p.link, 64);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
