package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: full snapshot of the combat the receiving player takes part in. */
public record CombatStatePayload(
		boolean active,
		boolean planning,
		int round,
		int activeIndex,
		float moveUsed,
		float moveBudget,
		float square,
		String unit,
		List<Res> resources,
		List<MoveButton> moves,
		List<Entry> entries
) implements CustomPayload {

	public static final Id<CombatStatePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "combat_state"));

	public static final PacketCodec<RegistryByteBuf, CombatStatePayload> CODEC = new PacketCodec<>() {
		@Override
		public CombatStatePayload decode(RegistryByteBuf buf) {
			boolean active = buf.readBoolean();
			boolean planning = buf.readBoolean();
			int round = buf.readVarInt();
			int activeIndex = buf.readVarInt();
			float moveUsed = buf.readFloat();
			float moveBudget = buf.readFloat();
			float square = buf.readFloat();
			String unit = buf.readString(16);
			int rn = buf.readVarInt();
			List<Res> resources = new ArrayList<>(rn);
			for (int i = 0; i < rn; i++) resources.add(new Res(buf.readString(32), buf.readVarInt(), buf.readVarInt()));
			int mn = buf.readVarInt();
			List<MoveButton> moves = new ArrayList<>(mn);
			for (int i = 0; i < mn; i++) moves.add(new MoveButton(buf.readString(32), buf.readString(48), buf.readString(48), buf.readBoolean()));
			int n = buf.readVarInt();
			List<Entry> entries = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				entries.add(Entry.read(buf));
			}
			return new CombatStatePayload(active, planning, round, activeIndex, moveUsed, moveBudget, square, unit, resources, moves, entries);
		}

		@Override
		public void encode(RegistryByteBuf buf, CombatStatePayload p) {
			buf.writeBoolean(p.active);
			buf.writeBoolean(p.planning);
			buf.writeVarInt(p.round);
			buf.writeVarInt(p.activeIndex);
			buf.writeFloat(p.moveUsed);
			buf.writeFloat(p.moveBudget);
			buf.writeFloat(p.square);
			buf.writeString(p.unit, 16);
			buf.writeVarInt(p.resources.size());
			for (Res r : p.resources) {
				buf.writeString(r.id, 32);
				buf.writeVarInt(r.left);
				buf.writeVarInt(r.max);
			}
			buf.writeVarInt(p.moves.size());
			for (MoveButton m : p.moves) {
				buf.writeString(m.id, 32);
				buf.writeString(m.label, 48);
				buf.writeString(m.cost, 48);
				buf.writeBoolean(m.enabled);
			}
			buf.writeVarInt(p.entries.size());
			for (Entry e : p.entries) {
				e.write(buf);
			}
		}
	};

	public static CombatStatePayload inactive() {
		return new CombatStatePayload(false, false, 0, 0, 0f, 0f, 1f, "", List.of(), List.of(), List.of());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

	/** A per-turn resource of the active combatant (action, bonus, ...). */
	public record Res(String id, int left, int max) {}

	/** A way to buy more movement that the player triggers with a button (cost is shown as text, e.g. "1 action"). */
	public record MoveButton(String id, String label, String cost, boolean enabled) {}

	/** One slot of the initiative bar. */
	public record Entry(int entityId, Identifier typeId, String playerName, float health, float maxHealth,
						boolean hostile, int initiative, boolean rolled, boolean rollable) {
		static Entry read(PacketByteBuf buf) {
			return new Entry(buf.readVarInt(), buf.readIdentifier(), buf.readString(), buf.readFloat(),
					buf.readFloat(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
		}

		void write(PacketByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeIdentifier(typeId);
			buf.writeString(playerName);
			buf.writeFloat(health);
			buf.writeFloat(maxHealth);
			buf.writeBoolean(hostile);
			buf.writeVarInt(initiative);
			buf.writeBoolean(rolled);
			buf.writeBoolean(rollable);
		}
	}
}
