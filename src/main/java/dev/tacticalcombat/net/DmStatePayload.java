package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server -> Dungeon Masters: the DM tool settings and the players online (shown when no fight is running). */
public record DmStatePayload(boolean autoMovement, List<Who> players) implements CustomPayload {
	public static final Id<DmStatePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dm_state"));

	/** One player: entity id, name, health (the sheet's when they have one), and whether they are in a fight. */
	public record Who(int entityId, String name, float hp, float max, boolean inCombat) {}

	public static final PacketCodec<RegistryByteBuf, DmStatePayload> CODEC = new PacketCodec<>() {
		@Override
		public DmStatePayload decode(RegistryByteBuf buf) {
			boolean auto = buf.readBoolean();
			int n = buf.readVarInt();
			List<Who> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(new Who(buf.readVarInt(), buf.readString(64), buf.readFloat(), buf.readFloat(), buf.readBoolean()));
			}
			return new DmStatePayload(auto, list);
		}

		@Override
		public void encode(RegistryByteBuf buf, DmStatePayload p) {
			buf.writeBoolean(p.autoMovement);
			buf.writeVarInt(p.players.size());
			for (Who w : p.players) {
				buf.writeVarInt(w.entityId);
				buf.writeString(w.name, 64);
				buf.writeFloat(w.hp);
				buf.writeFloat(w.max);
				buf.writeBoolean(w.inCombat);
			}
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
