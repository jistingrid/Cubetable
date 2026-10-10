package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server -> Dungeon Masters: the DM tool settings and the players online (shown when no fight is running). */
public record DmStatePayload(boolean autoMovement, List<Who> players, List<ActorInfo> actors) implements CustomPayload {
	public static final Id<DmStatePayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dm_state"));

	/** One player: entity id, name, health (the sheet's when they have one), and whether they are in a fight. */
	public record Who(int entityId, String name, float hp, float max, boolean inCombat) {}

	/** One Actor for the Actors window; entityId is -1 while it is not placed in the world. */
	public record ActorInfo(String id, String name, String sheetId, boolean ownsSheet, String kind, String value,
							int disposition, boolean dmControl, int entityId, float hp, float max, boolean inFight) {}

	public static final PacketCodec<RegistryByteBuf, DmStatePayload> CODEC = new PacketCodec<>() {
		@Override
		public DmStatePayload decode(RegistryByteBuf buf) {
			boolean auto = buf.readBoolean();
			int n = buf.readVarInt();
			List<Who> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(new Who(buf.readVarInt(), buf.readString(64), buf.readFloat(), buf.readFloat(), buf.readBoolean()));
			}
			int m = buf.readVarInt();
			List<ActorInfo> actors = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				actors.add(new ActorInfo(buf.readString(64), buf.readString(64), buf.readString(64), buf.readBoolean(),
						buf.readString(16), buf.readString(128), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
						buf.readFloat(), buf.readFloat(), buf.readBoolean()));
			}
			return new DmStatePayload(auto, list, actors);
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
			buf.writeVarInt(p.actors.size());
			for (ActorInfo a : p.actors) {
				buf.writeString(a.id, 64);
				buf.writeString(a.name, 64);
				buf.writeString(a.sheetId, 64);
				buf.writeBoolean(a.ownsSheet);
				buf.writeString(a.kind, 16);
				buf.writeString(a.value, 128);
				buf.writeVarInt(a.disposition);
				buf.writeBoolean(a.dmControl);
				buf.writeVarInt(a.entityId);
				buf.writeFloat(a.hp);
				buf.writeFloat(a.max);
				buf.writeBoolean(a.inFight);
			}
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
