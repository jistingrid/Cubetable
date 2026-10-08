package dev.tacticalcombat.card;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A character sheet entry (a spell, an item, a feature) shown to everyone: what the sender's sheet decided to print,
 * plus the colours of the sender's theme. The server only checks the size and relays it.
 *
 * @param stats  "Label: value" lines
 * @param colors bg, panel, edge, accent, muted, dim, banner_a, banner_b (ARGB)
 */
public record ShareCard(String character, String kind, String title, List<String> stats, String body, int[] colors) {
	public static final int MAX_STATS = 12;
	public static final int MAX_BODY = 600;
	public static final int COLORS = 8;

	public static ShareCard read(PacketByteBuf buf) {
		String character = buf.readString(48);
		String kind = buf.readString(24);
		String title = buf.readString(64);
		int n = Math.min(buf.readVarInt(), MAX_STATS);
		List<String> stats = new ArrayList<>();
		for (int i = 0; i < n; i++) stats.add(buf.readString(80));
		String body = buf.readString(MAX_BODY + 8);
		int[] colors = new int[COLORS];
		for (int i = 0; i < COLORS; i++) colors[i] = buf.readInt();
		return new ShareCard(character, kind, title, stats, body, colors);
	}

	public void write(PacketByteBuf buf) {
		buf.writeString(character, 48);
		buf.writeString(kind, 24);
		buf.writeString(title, 64);
		int n = Math.min(stats.size(), MAX_STATS);
		buf.writeVarInt(n);
		for (int i = 0; i < n; i++) buf.writeString(stats.get(i), 80);
		buf.writeString(body, MAX_BODY + 8);
		for (int i = 0; i < COLORS; i++) buf.writeInt(i < colors.length ? colors[i] : 0xFF000000);
	}

	/** Cuts everything to its limit and removes formatting / control characters. Used by the server and by the sender. */
	public ShareCard cleaned() {
		List<String> s = new ArrayList<>();
		for (String line : stats) {
			if (s.size() >= MAX_STATS) break;
			s.add(clean(line, 80, false));
		}
		return new ShareCard(clean(character, 48, false), clean(kind, 24, false), clean(title, 64, false), s,
				clean(body, MAX_BODY, true), colors.length == COLORS ? colors : java.util.Arrays.copyOf(colors, COLORS));
	}

	public static String clean(String text, int max, boolean keepNewlines) {
		if (text == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length() && sb.length() < max; i++) {
			char c = text.charAt(i);
			if (c == '§') continue;
			if (c == '\n' && keepNewlines) sb.append(c);
			else if (c >= 32) sb.append(c);
		}
		return sb.toString();
	}
}
