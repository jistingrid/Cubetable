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
	private final Map<String, Double> cache = new HashMap<>();
	private final Set<String> resolving = new HashSet<>();

	public SheetContext(SheetFormat format, CharacterData character) {
		this.format = format;
		this.character = character;
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
		if (formula == null) return null;
		if (!resolving.add(name)) throw new Expr.ExprException("'" + name + "' depends on itself");
		try {
			double v = Expr.eval(formula, this::lookup);
			cache.put(name, v);
			return v;
		} finally {
			resolving.remove(name);
		}
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
