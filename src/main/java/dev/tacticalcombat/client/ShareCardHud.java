package dev.tacticalcombat.client;

import dev.tacticalcombat.card.ShareCard;
import dev.tacticalcombat.net.ShareCardPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entries other players (or you) showed: a card on the HUD that fades on its own, can be dismissed with a key, and
 * can be brought back with the link posted in chat. Cards are only remembered until you leave the game.
 */
public final class ShareCardHud {
	private record Entry(long id, String player, ShareCard card) {}

	private static final int LIFE_TICKS = 220;
	private static final int FADE_TICKS = 8;
	private static final int MAX_HISTORY = 60;

	private static final Map<Long, Entry> HISTORY = new LinkedHashMap<>();
	private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<>();
	private static Entry current;
	private static int age;
	private static boolean persistent;
	private static int closing; // > 0 while fading out after a dismiss

	private ShareCardHud() {}

	public static boolean visible() {
		return current != null && closing == 0;
	}

	// ------------------------------------------------------------------ events

	public static void receive(ShareCardPayload p) {
		Entry e = new Entry(p.id(), p.player(), p.card());
		HISTORY.put(e.id, e);
		Iterator<Long> it = HISTORY.keySet().iterator();
		while (HISTORY.size() > MAX_HISTORY && it.hasNext()) {
			it.next();
			it.remove();
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			Style link = Style.EMPTY.withColor(Formatting.AQUA).withUnderline(true)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tcard " + e.id))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Show this card again")));
			String who = e.card.character().isBlank() ? e.player : e.card.character() + " (" + e.player + ")";
			mc.player.sendMessage(Text.literal("[Sheet] ").formatted(Formatting.GOLD)
					.append(Text.literal(who + " shows " + e.card.title() + " ").formatted(Formatting.WHITE))
					.append(Text.literal("[view]").setStyle(link)), false);
		}
		if (current == null) start(e, false);
		else QUEUE.add(e);
	}

	/** /tcard <id>: the chat link. The card stays until you dismiss it. */
	public static void recall(long id) {
		Entry e = HISTORY.get(id);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (e == null) {
			if (mc.player != null) {
				mc.player.sendMessage(Text.literal("[Sheet] That card has expired (cards are only kept until you leave the game).")
						.formatted(Formatting.RED), false);
			}
			return;
		}
		QUEUE.remove(e);
		start(e, true);
	}

	public static void dismiss() {
		if (current != null && closing == 0) closing = FADE_TICKS;
	}

	public static void clear() {
		HISTORY.clear();
		QUEUE.clear();
		current = null;
		closing = 0;
	}

	private static void start(Entry e, boolean stay) {
		current = e;
		age = 0;
		persistent = stay;
		closing = 0;
	}

	public static void tick(MinecraftClient client) {
		if (client.player == null) {
			clear();
			return;
		}
		if (current == null) {
			Entry next = QUEUE.poll();
			if (next != null) start(next, false);
			return;
		}
		age++;
		if (closing > 0) {
			if (--closing == 0) current = null;
		} else if (!persistent && age >= LIFE_TICKS) {
			closing = FADE_TICKS;
		}
	}

	// ------------------------------------------------------------------ drawing

	private static int fade(int argb, float k) {
		int a = Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, k)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** In-game HUD: only when no screen is open (screens draw it themselves, on top, see {@link #drawOverScreen}). */
	public static void render(DrawContext g, RenderTickCounter tickCounter) {
		if (MinecraftClient.getInstance().currentScreen != null) return;
		draw(g, tickCounter.getTickDelta(false));
	}

	/** After a screen has drawn itself (chat, the sheet window, the combat view...): the card goes above everything. */
	public static void drawOverScreen(DrawContext g, float partial) {
		g.getMatrices().push();
		g.getMatrices().translate(0, 0, 500);
		draw(g, partial);
		g.getMatrices().pop();
	}

	private static void draw(DrawContext g, float partial) {
		Entry e = current;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (e == null || mc.player == null) return;
		TextRenderer tr = mc.textRenderer;
		ShareCard c = e.card;
		float k = Math.min(1f, (age + partial) / FADE_TICKS);
		if (closing > 0) k = Math.min(k, (closing - partial) / FADE_TICKS);
		if (k < 0.06f) return;

		int[] col = c.colors();
		int bg = fade(col[0], k), panel = fade(col[1], k), edge = fade(col[2], k), accent = fade(col[3], k);
		int muted = fade(col[4], k), dim = fade(col[5], k), banA = fade(col[6], k), banB = fade(col[7], k);
		int white = fade(0xFFFFFFFF, k);

		int w = Math.min(210, g.getScaledWindowWidth() / 2);
		int x = 8;
		int y = 8;
		int maxBottom = g.getScaledWindowHeight() - 14;

		// content, so the panel can be sized to it (body and stats are drawn at 3/4 size)
		float s = 0.75f;
		List<OrderedText> body = c.body().isBlank() ? List.of()
				: tr.wrapLines(StringVisitable.plain(c.body()), (int) ((w - 14) / s));
		int headerH = 22;
		int whoH = 12;
		// stats flow left to right and wrap, so they use as little height as possible
		int[][] spot = new int[c.stats().size()][2];
		int statRows = 0;
		{
			int sx = 0;
			int row = 0;
			int maxW = w - 12;
			for (int i = 0; i < c.stats().size(); i++) {
				int iw = (int) Math.ceil(statWidth(tr, c.stats().get(i)) * s);
				if (sx > 0 && sx + iw > maxW) {
					row++;
					sx = 0;
				}
				spot[i][0] = sx;
				spot[i][1] = row;
				sx += iw + 8;
			}
			statRows = c.stats().isEmpty() ? 0 : row + 1;
		}
		int statsH = statRows * 9 + (c.stats().isEmpty() ? 0 : 3);
		int bodyLine = 8;
		int footerH = 12;
		int fixed = headerH + whoH + statsH + footerH + 6;
		int room = Math.max(0, (maxBottom - y - fixed) / bodyLine);
		int shown = Math.min(body.size(), room);
		boolean cut = shown < body.size();
		int h = fixed + shown * bodyLine + (shown > 0 ? 4 : 0);

		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, fade(0xFF05060A, k));
		g.fill(x, y, x + w, y + h, bg);
		g.fillGradient(x, y, x + w, y + headerH, banB, banA);
		g.fill(x, y + headerH - 1, x + w, y + headerH, fade(0xFF05060A, k));
		g.drawText(tr, tr.trimToWidth(c.title(), w - 12), x + 6, y + 7, white, true);

		int cy = y + headerH + 3;
		String who = (c.kind().isBlank() ? "" : c.kind() + "  -  ") + (c.character().isBlank() ? e.player : c.character() + " (" + e.player + ")");
		scaled(g, tr, who, x + 6, cy, s, muted, w - 12);
		cy += whoH - 2;

		if (!c.stats().isEmpty()) {
			g.fill(x + 6, cy, x + w - 6, cy + 1, edge);
			cy += 3;
			for (int i = 0; i < c.stats().size(); i++) {
				String line = c.stats().get(i);
				int lx = x + 6 + spot[i][0];
				int ly = cy + spot[i][1] * 9;
				int split = line.indexOf(": ");
				if (split > 0) {
					scaled(g, tr, line.substring(0, split), lx, ly, s, dim, w - 12);
					scaled(g, tr, line.substring(split + 2), lx + (int) ((tr.getWidth(line.substring(0, split)) + 3) * s), ly, s, white, w - 12);
				} else {
					scaled(g, tr, line, lx, ly, s, accent, w - 12);
				}
			}
			cy += statRows * 9 + 2;
		}

		if (shown > 0) {
			g.fill(x + 6, cy, x + w - 6, cy + 1, edge);
			cy += 4;
			var ms = g.getMatrices();
			ms.push();
			ms.translate(x + 6, cy, 0);
			ms.scale(s, s, 1f);
			int ly = 0;
			for (int i = 0; i < shown; i++) {
				String text = null;
				OrderedText line = body.get(i);
				if (cut && i == shown - 1) {
					// last visible line of a cut text ends in "..."
					StringBuilder sb = new StringBuilder();
					line.accept((idx, style, cp) -> {
						sb.appendCodePoint(cp);
						return true;
					});
					text = tr.trimToWidth(sb.toString(), (int) ((w - 14) / s) - tr.getWidth("...")) + "...";
				}
				if (text != null) g.drawText(tr, text, 0, ly, muted, false);
				else g.drawText(tr, line, 0, ly, muted, false);
				ly += (int) (bodyLine / s);
			}
			ms.pop();
			cy += shown * bodyLine;
		}

		// footer: key hint and the time left
		String key = TacticalCombatClient.DISMISS_CARD_KEY.getBoundKeyLocalizedText().getString();
		scaled(g, tr, key + ": dismiss", x + 6, y + h - footerH + 2, s, dim, w - 12);
		if (!persistent && closing == 0) {
			float left = 1f - age / (float) LIFE_TICKS;
			g.fill(x, y + h - 2, x + Math.round(w * Math.max(0f, left)), y + h, accent);
		}
	}

	/** Unscaled width of one stat ("Label: value" or a lone flag). */
	private static int statWidth(TextRenderer tr, String line) {
		int split = line.indexOf(": ");
		return split > 0 ? tr.getWidth(line.substring(0, split)) + 3 + tr.getWidth(line.substring(split + 2)) : tr.getWidth(line);
	}

	private static void scaled(DrawContext g, TextRenderer tr, String text, int x, int y, float s, int color, int maxWidth) {
		var ms = g.getMatrices();
		ms.push();
		ms.translate(x, y, 0);
		ms.scale(s, s, 1f);
		g.drawText(tr, tr.trimToWidth(text, (int) (maxWidth / s)), 0, 0, color, false);
		ms.pop();
	}
}
