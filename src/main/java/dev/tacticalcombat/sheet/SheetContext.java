package dev.tacticalcombat.sheet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A character seen through its format: resolves stored values and calculated ones, and prints / rolls formulas. */
public final class SheetContext {
	public final SheetFormat format;
	public final CharacterData character;
	public final SheetFormat.Layout layout;
	private final Map<String, Double> cache = new HashMap<>();
	private final Set<String> resolving = new HashSet<>();

	public SheetContext(SheetFormat format, CharacterData character) {
		this.format = format;
		this.character = character;
		this.layout = format.layout(character.kind);
	}

	/** Value of a name, or null if neither the character nor the format knows it. */
	public Double lookup(String name) {
		name = name.toLowerCase(Locale.ROOT);
		Double stored = character.values.get(name);
		if (stored != null) return stored;
		Double fallback = format.defaults.get(name);
		if (fallback != null) return fallback;
		Double done = cache.get(name);
		if (done != null) return done;
		String formula = format.derived.get(name);
		if (formula == null) return aggregate(name);
		if (!resolving.add(name)) throw new Expr.ExprException("'" + name + "' depends on itself");
		try {
			double v = Expr.eval(formula, this::lookup);
			cache.put(name, v);
			return v;
		} finally {
			resolving.remove(name);
		}
	}

	// ------------------------------------------------------------------ collections

	/** count, count.col, sum.col, max.col, sumif.flag.col over a collection's rows: "gear.sum.line_weight". */
	private Double aggregate(String name) {
		String[] p = name.split("\\.");
		if (p.length < 2) return null;
		SheetFormat.Collection c = format.collections.get(p[0]);
		if (c == null) return null;
		if (!resolving.add(name)) throw new Expr.ExprException("'" + name + "' depends on itself");
		try {
			java.util.List<CharacterData.Row> rows = character.collections.getOrDefault(c.id, java.util.List.of());
			switch (p[1]) {
				case "count":
					if (p.length == 2) return (double) rows.size();
					if (p.length == 3) {
						double n = 0;
						for (CharacterData.Row r : rows) if (columnNumber(c, r, p[2]) > 0) n++;
						return n;
					}
					break;
				case "sum":
				case "max":
					if (p.length == 3) {
						double acc = 0;
						boolean first = true;
						for (CharacterData.Row r : rows) {
							double v = columnNumber(c, r, p[2]);
							acc = p[1].equals("sum") ? acc + v : (first ? v : Math.max(acc, v));
							first = false;
						}
						return acc;
					}
					break;
				case "sumif":
					if (p.length == 4) {
						double acc = 0;
						for (CharacterData.Row r : rows) if (columnNumber(c, r, p[2]) > 0) acc += columnNumber(c, r, p[3]);
						return acc;
					}
					break;
				default:
			}
			throw new Expr.ExprException("don't know '" + name + "'");
		} finally {
			resolving.remove(name);
		}
	}

	/** The number a column holds in a row (stored value, default, or the result of its formula). */
	public double columnNumber(SheetFormat.Collection c, CharacterData.Row row, String colId) {
		SheetFormat.Col col = c.column(colId);
		if (col == null) throw new Expr.ExprException("collection '" + c.id + "' has no column '" + colId + "'");
		switch (col.type) {
			case "computed":
			case "roll": {
				String guard = "row:" + c.id + "." + colId + "@" + System.identityHashCode(row);
				if (col.value == null) throw new Expr.ExprException("column '" + colId + "' has no number");
				if (!resolving.add(guard)) throw new Expr.ExprException("column '" + colId + "' depends on itself");
				try {
					return Expr.eval(col.value, rowVars(c, row));
				} finally {
					resolving.remove(guard);
				}
			}
			case "number":
			case "toggle":
			case "choice":
				return row.values.getOrDefault(colId, col.def);
			default:
				throw new Expr.ExprException("column '" + colId + "' is text, not a number");
		}
	}

	/** Names inside a row formula: row.<column>, everything else comes from the sheet. */
	public java.util.function.Function<String, Double> rowVars(SheetFormat.Collection c, CharacterData.Row row) {
		return n -> {
			if (n.startsWith("row.")) return columnNumber(c, row, n.substring(4));
			return lookup(n);
		};
	}

	public double number(SheetFormat.Collection c, CharacterData.Row row, String formula) {
		try {
			return Expr.eval(formula, rowVars(c, row));
		} catch (RuntimeException e) {
			return Double.NaN;
		}
	}

	public String error(SheetFormat.Collection c, CharacterData.Row row, String formula) {
		try {
			Expr.eval(formula, rowVars(c, row));
			return null;
		} catch (RuntimeException e) {
			return e.getMessage();
		}
	}

	public Expr.Roll plan(SheetFormat.Collection c, CharacterData.Row row, String formula) {
		return Expr.roll(formula, rowVars(c, row));
	}

	public static String format(double v, boolean signed) {
		if (Double.isNaN(v)) return "?";
		long r = Math.round(v);
		String s = Math.abs(v - r) < 1.0E-9 ? Long.toString(Math.abs(r)) : String.format(Locale.ROOT, "%.1f", Math.abs(v));
		if (!signed) return (v < 0 ? "-" : "") + s;
		return (v < 0 ? "-" : "+") + s;
	}

	/** Number for a formula, or NaN if it does not work (the window shows "?" and the reason in a tooltip). */
	public double number(String formula) {
		try {
			return Expr.eval(formula, this::lookup);
		} catch (RuntimeException e) {
			return Double.NaN;
		}
	}

	public String error(String formula) {
		try {
			Expr.eval(formula, this::lookup);
			return null;
		} catch (RuntimeException e) {
			return e.getMessage();
		}
	}

	public String show(String formula, boolean signed) {
		double v = number(formula);
		if (Double.isNaN(v)) return "?";
		long r = Math.round(v);
		String s = Math.abs(v - r) < 1.0E-9 ? Long.toString(Math.abs(r)) : String.format(Locale.ROOT, "%.1f", Math.abs(v));
		if (!signed) return (v < 0 ? "-" : "") + s;
		return (v < 0 ? "-" : "+") + s;
	}

	/** True when an optional "enabled" formula allows showing something (no formula, or not calculable = shown). */
	public boolean enabled(String formula) {
		if (formula == null || formula.isBlank()) return true;
		double v = number(formula);
		return Double.isNaN(v) || v > 0;
	}

	/** Replaces $name / $subtitle with the character's text fields. */
	public String template(String template) {
		String out = template;
		for (Map.Entry<String, String> e : character.texts.entrySet()) {
			out = out.replace("$" + e.getKey(), e.getValue());
		}
		return out.replaceAll("\\$[a-z_]+", "").trim();
	}

	public Expr.Roll plan(String formula) {
		return Expr.roll(formula, this::lookup);
	}
}
