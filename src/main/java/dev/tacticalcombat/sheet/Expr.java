package dev.tacticalcombat.sheet;

import dev.tacticalcombat.dice.DiceType;

import java.util.Locale;
import java.util.function.Function;

/**
 * Tiny expression language for sheet formats: numbers, names, + - * / ( ), floor() ceil() round() abs() min() max() pick(),
 * and (for rolls only) one dice term like d20, 2d6 or d%, which may only be added: {@code d20 + str_mod + prof}.
 */
public final class Expr {
	/** What a roll formula asks for: N dice of one type, plus a flat modifier. */
	public record Roll(DiceType type, int count, int modifier) {}

	public static final class ExprException extends RuntimeException {
		public ExprException(String message) {
			super(message);
		}
	}

	private final String src;
	private final Function<String, Double> vars;
	private int pos;
	private DiceType diceType;
	private int diceCount;
	private final boolean allowDice;

	private Expr(String src, Function<String, Double> vars, boolean allowDice) {
		this.src = src;
		this.vars = vars;
		this.allowDice = allowDice;
	}

	/** Evaluates a plain number formula such as {@code floor((str - 10) / 2)}. */
	public static double eval(String formula, Function<String, Double> vars) {
		Expr e = new Expr(formula, vars, false);
		double v = e.parseExpr();
		e.expectEnd();
		return v;
	}

	/** Evaluates a roll formula such as {@code d20 + str_mod + prof} or {@code 1d8 + 3}. */
	public static Roll roll(String formula, Function<String, Double> vars) {
		Expr e = new Expr(formula, vars, true);
		double mod = e.parseExpr();
		e.expectEnd();
		if (e.diceType == null) throw new ExprException("no dice in '" + formula + "'");
		return new Roll(e.diceType, e.diceCount, (int) Math.round(mod));
	}

	private void expectEnd() {
		skipSpace();
		if (pos < src.length()) throw new ExprException("unexpected '" + src.charAt(pos) + "' in '" + src + "'");
	}

	private void skipSpace() {
		while (pos < src.length() && src.charAt(pos) == ' ') pos++;
	}

	private boolean accept(char c) {
		skipSpace();
		if (pos < src.length() && src.charAt(pos) == c) {
			pos++;
			return true;
		}
		return false;
	}

	private double parseExpr() {
		double v = parseTerm();
		while (true) {
			if (accept('+')) v += parseTerm();
			else if (accept('-')) {
				// dice can only be added
				int before = diceCount;
				v -= parseTerm();
				if (diceCount != before) throw new ExprException("dice can not be subtracted in '" + src + "'");
			} else return v;
		}
	}

	private double parseTerm() {
		double v = parseFactor();
		while (true) {
			if (accept('*')) v *= parseFactor();
			else if (accept('/')) {
				double d = parseFactor();
				v = d == 0 ? 0 : v / d;
			} else return v;
		}
	}

	private double parseFactor() {
		skipSpace();
		if (accept('-')) return -parseFactor();
		if (accept('(')) {
			double v = parseExpr();
			if (!accept(')')) throw new ExprException("missing ) in '" + src + "'");
			return v;
		}
		if (pos >= src.length()) throw new ExprException("unexpected end of '" + src + "'");

		char c = src.charAt(pos);
		if (Character.isDigit(c) || c == '.') {
			int start = pos;
			while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
			double number = Double.parseDouble(src.substring(start, pos));
			// "2d6": a number directly followed by d + sides
			if (pos < src.length() && src.charAt(pos) == 'd' && pos + 1 < src.length()
					&& (Character.isDigit(src.charAt(pos + 1)) || src.charAt(pos + 1) == '%')) {
				return parseDice((int) number);
			}
			return number;
		}
		if (Character.isLetter(c) || c == '_') {
			int start = pos;
			while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_' || src.charAt(pos) == '.')) pos++;
			String name = src.substring(start, pos).toLowerCase(Locale.ROOT);
			// "d20", "d%" (a lone die)
			if (name.matches("d\\d+")) {
				pos = start;
				return parseDice(1);
			}
			if (name.equals("d") && pos < src.length() && src.charAt(pos) == '%') {
				pos = start;
				return parseDice(1);
			}
			if (accept('(')) return call(name);
			Double v = vars.apply(name);
			if (v == null) throw new ExprException("unknown name '" + name + "'");
			return v;
		}
		throw new ExprException("unexpected '" + c + "' in '" + src + "'");
	}

	/** At pos: 'd' then sides digits or '%'. */
	private double parseDice(int count) {
		if (!allowDice) throw new ExprException("dice are only allowed in rolls: '" + src + "'");
		if (diceType != null) throw new ExprException("only one kind of dice per roll: '" + src + "'");
		pos++; // the d
		int start = pos;
		if (pos < src.length() && src.charAt(pos) == '%') pos++;
		else while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
		DiceType type = DiceType.fromSides(src.substring(start, pos));
		if (type == null) throw new ExprException("no such die in '" + src + "'");
		diceType = type;
		diceCount = count;
		return 0;
	}

	private double call(String fn) {
		java.util.List<Double> args = new java.util.ArrayList<>();
		args.add(parseExpr());
		while (accept(',')) args.add(parseExpr());
		if (!accept(')')) throw new ExprException("missing ) after " + fn + "(");
		double a = args.get(0);
		switch (fn) {
			case "floor": return Math.floor(a);
			case "ceil": return Math.ceil(a);
			case "round": return Math.round(a);
			case "abs": return Math.abs(a);
			case "min": {
				double m = a;
				for (double d : args) m = Math.min(m, d);
				return m;
			}
			case "max": {
				double m = a;
				for (double d : args) m = Math.max(m, d);
				return m;
			}
			case "pick": {
				// pick(index, first, second, ...): the option at that index (clamped), for "choice" columns
				if (args.size() < 2) throw new ExprException("pick() needs an index and options in '" + src + "'");
				int idx = (int) Math.round(a);
				idx = Math.max(0, Math.min(args.size() - 2, idx));
				return args.get(idx + 1);
			}
			default: throw new ExprException("unknown function " + fn + "()");
		}
	}
}
