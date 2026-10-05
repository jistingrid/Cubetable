package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.grid.Grid;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Server -> client: the squares the active player may walk to (with path parents) and the squares
 * enemies could reach / hit next turn. An empty payload clears the highlights.
 */
public record GridPayload(List<Cell> cells, long[] threat) implements CustomPayload {
	private static final int MAX_THREAT = 4000;

	public static final Id<GridPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "grid"));

	public static final PacketCodec<RegistryByteBuf, GridPayload> CODEC = new PacketCodec<>() {
		@Override
		public GridPayload decode(RegistryByteBuf buf) {
			int n = buf.readVarInt();
			List<Cell> cells = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				cells.add(new Cell(buf.readLong(), buf.readVarInt(), buf.readVarInt() - 1, buf.readBoolean()));
			}
			long[] threat = new long[buf.readVarInt()];
			for (int i = 0; i < threat.length; i++) {
				threat[i] = buf.readLong();
			}
			return new GridPayload(cells, threat);
		}

		@Override
		public void encode(RegistryByteBuf buf, GridPayload p) {
			buf.writeVarInt(p.cells.size());
			for (Cell c : p.cells) {
				buf.writeLong(c.pos);
				buf.writeVarInt(c.cost);
				buf.writeVarInt(c.parent + 1);
				buf.writeBoolean(c.endable);
			}
			buf.writeVarInt(p.threat.length);
			for (long l : p.threat) {
				buf.writeLong(l);
			}
		}
	};

	/** One walkable square. {@code parent} is an index into the same list, -1 for the start square. */
	public record Cell(long pos, int cost, int parent, boolean endable) {}

	public static GridPayload empty() {
		return new GridPayload(List.of(), new long[0]);
	}

	public static GridPayload of(Grid.Result result, Set<Long> threat) {
		List<Cell> cells = new ArrayList<>(result.nodes.size());
		for (Grid.Node n : result.nodes) {
			cells.add(new Cell(n.pos, n.cost, n.parent, n.endable));
		}
		int count = Math.min(threat.size(), MAX_THREAT);
		long[] t = new long[count];
		int i = 0;
		for (long l : threat) {
			if (i >= count) break;
			t[i++] = l;
		}
		return new GridPayload(cells, t);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
