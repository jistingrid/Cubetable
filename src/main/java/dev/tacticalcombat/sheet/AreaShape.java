package dev.tacticalcombat.sheet;

import java.util.Locale;

/**
 * The area or number of targets an entry (a spell, a feature, an attack) affects, read from the text of the pack's
 * area column: {@code sphere 20}, {@code cube 15}, {@code cone 15}, {@code line 30x5} (length x width) or
 * {@code targets 3}. Sizes are in the game's unit and are divided by the size of one grid square. Pure maths on grid
 * cells, no Minecraft.
 */
public record AreaShape(String kind, double length, double width, int count, boolean repeat, boolean separate) {
	public static final String SPHERE = "sphere", CUBE = "cube", CONE = "cone", LINE = "line", TARGETS = "targets";

	/** The shape written in {@code text}, or null when it is blank or not understood. {@code square}: the unit length of one grid square. */
	public static AreaShape parse(String text, double square) {
		if (text == null) return null;
		String t = text.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty()) return null;
		String[] w = t.split("[\\s:]+", 2);
		String kind = switch (w[0]) {
			case "sphere", "circle", "radius", "burst", "emanation" -> SPHERE;
			case "cube", "square", "box" -> CUBE;
			case "cone" -> CONE;
			case "line", "beam" -> LINE;
			case "targets", "target", "creatures", "n", "darts", "missiles", "rays" -> TARGETS;
			default -> null;
		};
		if (kind == null || w.length < 2) return null;
		// "targets 3": up to 3 different creatures. "targets 3 repeat" (or darts / missiles / rays): the same creature may be picked again
		boolean separate = w[1].contains("separate") || w[1].contains("each") || w[1].contains("own");
		boolean repeat = w[0].equals("darts") || w[0].equals("missiles") || w[0].equals("rays")
				|| w[1].contains("repeat") || w[1].contains("again") || w[1].contains("same");
		double sq = square <= 0 ? 1 : square;
		String[] size = w[1].replaceAll("[a-z. ]*$", "").trim().split("\\s*x\\s*");
		try {
			double a = Double.parseDouble(size[0].replaceAll("[^0-9.]", ""));
			if (a <= 0) return null;
			if (kind.equals(TARGETS)) return new AreaShape(kind, 0, 0, Math.min(32, (int) Math.round(a)), repeat, separate || repeat); // darts are rolled one by one
			double width = size.length > 1 ? Double.parseDouble(size[1].replaceAll("[^0-9.]", "")) / sq : 1;
			return new AreaShape(kind, Math.max(1, Math.round(a / sq)), Math.max(1, width), 0, false, false);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public boolean isCount() {
		return kind.equals(TARGETS);
	}

	/** Cones and lines start at the caster and point at the aimed square; spheres and cubes are centred on it. */
	public boolean fromCaster() {
		return kind.equals(CONE) || kind.equals(LINE);
	}

	/**
	 * Is the cell (cx, cz) inside the shape? (ox, oz): the caster's cell; (ax, az): the aimed cell.
	 */
	public boolean contains(int ox, int oz, int ax, int az, int cx, int cz) {
		switch (kind) {
			case SPHERE: {
				double r = length;
				double dx = cx - ax, dz = cz - az;
				return dx * dx + dz * dz <= r * r + r;
			}
			case CUBE: {
				int s = (int) length;
				int lo = -((s - 1) / 2), hi = s / 2;
				return cx - ax >= lo && cx - ax <= hi && cz - az >= lo && cz - az <= hi;
			}
			case CONE:
			case LINE: {
				double dx = ax - ox, dz = az - oz;
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d < 1.0E-9) return false;
				dx /= d;
				dz /= d;
				double vx = cx - ox, vz = cz - oz;
				double proj = vx * dx + vz * dz;
				double perp = Math.abs(-vx * dz + vz * dx);
				if (proj < 0.5 || proj > length + 0.5) return false;
				return kind.equals(CONE) ? perp <= proj * 0.5 + 0.5 : perp <= width / 2 + 1.0E-6;
			}
			default:
				return false;
		}
	}

	/** Short text for the title of the aiming panel. */
	public String describe(String unit, double square) {
		double sq = square <= 0 ? 1 : square;
		String u = unit == null ? "" : " " + unit;
		return switch (kind) {
			case TARGETS -> count + (repeat ? " hits, creatures may be picked again" : count == 1 ? " target" : " targets") + (separate ? ", rolled separately" : "");
			case LINE -> Math.round(length * sq) + u + " line";
			default -> Math.round(length * sq) + u + " " + kind;
		};
	}
}
