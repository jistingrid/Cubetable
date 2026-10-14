package dev.tacticalcombat.codex;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * How a codex entry is named inside a game. The key is {@code <format id>/<type>/<slug of the entry's name>}, so it does
 * not depend on which file the entry sits in (moving or renaming a file never makes a second copy in the game). The
 * server keeps an added entry under an id worked out from its key, so the client and the server agree on it without
 * talking, adding the same entry again updates it, and the client can tell which entries are already in the game.
 */
public final class CodexKeys {
	private CodexKeys() {}

	/** Lower case letters and digits, single underscores in between; never empty. */
	public static String slug(String name) {
		String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (s.length() > 60) s = s.substring(0, 60).replaceAll("_+$", "");
		return s.isEmpty() ? "entry" : s;
	}

	public static String key(String formatId, String type, String name) {
		return formatId + "/" + type + "/" + slug(name);
	}

	/** True for a key of the shape {@link #key} makes (the server refuses anything else). */
	public static boolean valid(String key) {
		return key != null && key.matches("[A-Za-z0-9_\\-]{1,40}/[a-z]{1,20}/[a-z0-9_]{1,60}");
	}

	/** The id the server stores an added entry under (a server character id). */
	public static String idOf(String key) {
		return UUID.nameUUIDFromBytes(("tacticalcombat:codex:" + key).getBytes(StandardCharsets.UTF_8)).toString();
	}

	/** The format id at the front of a key. */
	public static String formatOf(String key) {
		int i = key.indexOf('/');
		return i < 0 ? "" : key.substring(0, i);
	}
}
