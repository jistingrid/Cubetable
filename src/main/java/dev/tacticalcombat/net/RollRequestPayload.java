package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Master -> server: ask these creatures (entity ids) for a roll. {@code check} is an id of the game pack's
 * {@code combat.checks} (empty: a plain roll of the game's default die), {@code dc} the number to beat (-1: none), {@code showDc} whether the
 * players are told it.
 */
public record RollRequestPayload(String check, int dc, boolean showDc, List<Integer> targets) implements CustomPayload {
	public static final Id<RollRequestPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "roll_request"));

	public static final PacketCodec<RegistryByteBuf, RollRequestPayload> CODEC = new PacketCodec<>() {
		@Override
		public RollRequestPayload decode(RegistryByteBuf buf) {
			String check = buf.readString(40);
			int dc = buf.readVarInt() - 1;
			boolean show = buf.readBoolean();
			int n = Math.min(buf.readVarInt(), 128);
			List<Integer> ids = new ArrayList<>();
			for (int i = 0; i < n; i++) ids.add(buf.readVarInt());
			return new RollRequestPayload(check, dc, show, ids);
		}

		@Override
		public void encode(RegistryByteBuf buf, RollRequestPayload p) {
			buf.writeString(p.check, 40);
			buf.writeVarInt(p.dc + 1);
			buf.writeBoolean(p.showDc);
			int n = Math.min(p.targets.size(), 128);
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) buf.writeVarInt(p.targets.get(i));
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
