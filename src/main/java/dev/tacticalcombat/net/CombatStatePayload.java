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
		int round,
		int activeIndex,
		float moveUsed,
		float moveBudget,
		boolean actionUsed,
		boolean bonusUsed,
		List<Entry> entries
) implements CustomPayload {

	public static final Id<CombatStatePayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "combat_state"));

	public static final PacketCodec<RegistryByteBuf, CombatStatePayload> CODEC = new PacketCodec<>() {
		@Override
		public CombatStatePayload decode(RegistryByteBuf buf) {
			boolean active = buf.readBoolean();
			int round = buf.readVarInt();
			int activeIndex = buf.readVarInt();
			float moveUsed = buf.readFloat();
			float moveBudget = buf.readFloat();
			boolean actionUsed = buf.readBoolean();
			boolean bonusUsed = buf.readBoolean();
			int n = buf.readVarInt();
			List<Entry> entries = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				entries.add(Entry.read(buf));
			}
			return new CombatStatePayload(active, round, activeIndex, moveUsed, moveBudget, actionUsed, bonusUsed, entries);
		}

		@Override
		public void encode(RegistryByteBuf buf, CombatStatePayload p) {
			buf.writeBoolean(p.active);
			buf.writeVarInt(p.round);
			buf.writeVarInt(p.activeIndex);
			buf.writeFloat(p.moveUsed);
			buf.writeFloat(p.moveBudget);
			buf.writeBoolean(p.actionUsed);
			buf.writeBoolean(p.bonusUsed);
			buf.writeVarInt(p.entries.size());
			for (Entry e : p.entries) {
				e.write(buf);
			}
		}
	};

	public static CombatStatePayload inactive() {
		return new CombatStatePayload(false, 0, 0, 0f, 0f, false, false, List.of());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

	/** One slot of the initiative bar. */
	public record Entry(int entityId, Identifier typeId, String playerName, float health, float maxHealth,
						boolean hostile, int initiative) {
		static Entry read(PacketByteBuf buf) {
			return new Entry(buf.readVarInt(), buf.readIdentifier(), buf.readString(), buf.readFloat(),
					buf.readFloat(), buf.readBoolean(), buf.readVarInt());
		}

		void write(PacketByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeIdentifier(typeId);
			buf.writeString(playerName);
			buf.writeFloat(health);
			buf.writeFloat(maxHealth);
			buf.writeBoolean(hostile);
			buf.writeVarInt(initiative);
		}
	}
}
