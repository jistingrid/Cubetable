package dev.tacticalcombat.client;

import dev.tacticalcombat.grid.Grid;
import dev.tacticalcombat.net.GridPayload;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The highlighted squares the server sent, plus what the mouse currently points at. */
public final class ClientGrid {
	public static List<GridPayload.Cell> cells = List.of();
	/** Squares enemies could reach or hit (red). */
	public static Set<Long> threat = Set.of();

	/** Index into {@link #cells} of the square under the cursor, -1 if none. */
	public static int hover = -1;
	/** Entity id of the hostile under the cursor, -1 if none. */
	public static int hoverEntity = -1;

	private static final Map<Long, Integer> INDEX = new HashMap<>();
	private static final Map<Long, Float> SURFACE = new HashMap<>();

	private ClientGrid() {}

	public static void apply(GridPayload p) {
		cells = p.cells();
		INDEX.clear();
		for (int i = 0; i < cells.size(); i++) {
			INDEX.put(cells.get(i).pos(), i);
		}
		Set<Long> t = new HashSet<>();
		for (long l : p.threat()) {
			t.add(l);
		}
		threat = t;
		SURFACE.clear();
		hover = -1;
		BlockFade.markDirty(); // squares hidden behind faded blocks must be re-evaluated for the new grid
	}

	public static void clear() {
		cells = List.of();
		threat = Set.of();
		INDEX.clear();
		SURFACE.clear();
		hover = -1;
		hoverEntity = -1;
	}

	public static Integer indexOf(long pos) {
		return INDEX.get(pos);
	}

	/** Height the highlight of a square is drawn at (cached per grid). */
	public static float surface(World world, long pos) {
		Float cached = SURFACE.get(pos);
		if (cached != null) return cached;
		float y = (float) Grid.surfaceY(world, BlockPos.fromLong(pos));
		SURFACE.put(pos, y);
		return y;
	}

	/** Indices from the start square to {@code idx}, in walking order. */
	public static List<Integer> pathTo(int idx) {
		LinkedList<Integer> out = new LinkedList<>();
		int guard = 0;
		while (idx >= 0 && idx < cells.size() && guard++ < 256) {
			out.addFirst(idx);
			idx = cells.get(idx).parent();
		}
		return out;
	}
}
