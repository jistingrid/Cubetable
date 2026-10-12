package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.net.DiceRequestPayload;
import dev.tacticalcombat.net.EncounterActionPayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.Expr;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bar at the bottom centre of the combat view: your health bars, the resources of the turn, movement (with the
 * moves you can buy), the slots the pack lists in its "actions" block, and End Turn.
 *
 * <p>Slots whose source names a <b>tray</b> (spells, features ...) are not on the bar: the bar has a button for the tray
 * (key {@code T}) that opens a scrollable list grouped under headings, and any entry can be <b>pinned</b> from there to
 * the bar. The small "..." button at the right opens the player's own settings (size, opacity, what to show), kept in
 * {@link ActionBarConfig}. Everything but the frame comes from the pack and the sheet, so a game without actions or bars
 * simply gets a smaller bar.
 *
 * <p>Drawn from the HUD callback every frame, which also records where every control is (the "hits"); clicks, the wheel
 * and number keys are routed in by {@link TacticalScreen} and use that record.
 */
public final class ActionBar {
	private static final int PAD = 5;
	private static final int SLOT = 24;
	private static final int GAP = 3;
	private static final int ROW = 11;
	private static final int SEP = 9;
	private static final int MAX_SLOTS = 9;
	/** Distance from the bottom of the screen in the default place (Minecraft's own hotbar is hidden in a fight). */
	private static final int MARGIN = 8;

	private static final int EDGE = 0xFF3A3F4D;
	private static final int EDGE_LIGHT = 0xFF565D70;
	private static final int ACCENT = 0xFFE0B84C;
	private static final int TEXT = 0xFFE8E6DF;
	private static final int MUTED = 0xFF9AA0AE;
	private static final int DIM = 0xFF6A7080;
	private static final int ACTION = 0xFFE0B84C;
	private static final int BONUS = 0xFF5FB7A8;
	private static final int OTHER = 0xFFB48CFF;
	private static final int SPENT = 0xFF3C4150;
	private static final int MOVE = 0xFFE0B84C;
	private static final int SLOT_TOP = 0xFF2C3242;
	private static final int SLOT_BOTTOM = 0xFF1F2330;

	private static final Map<String, ItemStack> ICONS = new HashMap<>();
	private static final Map<String, Long> FLASH = new HashMap<>();

	private static boolean dragging;
	/** Index (in the format's trays) of the open tray, or -1. */
	private static int openTray = -1;
	private static boolean settingsOpen;
	/** First row shown in the open tray. */
	private static int trayScroll;

	private ActionBar() {}

	/** One slot, already evaluated on the character's sheet; the references let a click roll it. */
	public record Slot(String name, ItemStack icon, int level, Map<String, Integer> cost, String costText,
					   int left, int max, boolean usable,
					   SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, boolean own, String store,
					   String key, String character, String tray, String group, int groupOrder, String info, boolean pinned) {}

	/** An entry is used (not rolled) when it has an activate column, or "I use it" rules and nothing to attack with. */
	private static boolean activates(SheetFormat.Collection coll, CharacterData.Row row) {
		for (SheetFormat.Col col : coll.columns) if (col.type.equals("activate")) return true;
		for (dev.tacticalcombat.sheet.ConditionRules.Rule r : dev.tacticalcombat.sheet.ConditionRules.parse(dev.tacticalcombat.sheet.ConditionRules.rowRules(coll, row))) {
			if (r.when().equals("used")) return !coll.targetable;
		}
		return false;
	}

	private record Built(List<Slot> all, List<CombatRules.Tray> trays) {}

	/** A place on the screen that does something when clicked (virtual, scaled coordinates). */
	private record Hit(int x, int y, int w, int h, Runnable left, Runnable right, List<Text> tip) {
		boolean at(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static final List<Hit> HITS = new ArrayList<>();
	/** Where the bar and the open pop-up were drawn last frame (virtual coordinates), or null. */
	private static int[] barRect;
	private static int[] popRect;
	private static float lastScale = 1f;
	private static List<Slot> hotbar = List.of();
	private static List<CombatRules.Tray> lastTrays = List.of();

	// ------------------------------------------------------------------ data

	/**
	 * Whose bar this is: your own combatant, or, for a Dungeon Master walking a creature by hand, the creature on turn
	 * (its sheet gives the slots, so the DM rolls as that creature).
	 */
	private static CombatStatePayload.Entry mine(MinecraftClient mc) {
		if (mc.player == null) return null;
		if (!ClientCombatState.isMyTurn() && ClientCombatState.canMoveActive()) {
			int idx = ClientCombatState.activeIndex;
			if (idx >= 0 && idx < ClientCombatState.entries.size()) return ClientCombatState.entries.get(idx);
		}
		for (CombatStatePayload.Entry e : ClientCombatState.entries) {
			if (e.entityId() == mc.player.getId()) return e;
		}
		return null;
	}

	/** The bars to show for someone: their sheet's, else a plain health bar from the creature itself. */
	private static List<CombatStatePayload.Bar> barsOf(CombatStatePayload.Entry e) {
		if (!e.bars().isEmpty()) return e.bars();
		if (e.maxHealth() <= 0) return List.of();
		return List.of(new CombatStatePayload.Bar("hp", "HP", e.health(), e.maxHealth(), 0xFFE04040, true));
	}

	private static Built buildSlots(CombatStatePayload.Entry mine, boolean myTurn) {
		if (mine == null) return new Built(List.of(), List.of());
		MinecraftClient mcl = MinecraftClient.getInstance();
		boolean own = mcl.player != null && mine.entityId() == mcl.player.getId();
		CharacterData c = own ? ServerCharacters.fighter(mine.sheetId()) : ServerCharacters.byId(mine.sheetId());
		if (c == null) return new Built(List.of(), List.of());
		SheetFormat f = SheetLibrary.FORMATS.get(c.format);
		if (f == null || f.combat == null || f.combat.sources.isEmpty()) return new Built(List.of(), List.of());
		CombatRules rules = f.combat;
		SheetContext sc = new SheetContext(f, c);
		String main = rules.resources.isEmpty() ? "" : rules.resources.keySet().iterator().next();
		String character = c.link.isEmpty() ? c.displayName() : c.link;

		List<Slot> out = new ArrayList<>();
		for (CombatRules.Source s : rules.sources) {
			SheetFormat.Collection coll = f.collections.get(s.collection());
			if (coll == null) continue;
			String infoCol = s.info().isEmpty() ? "notes" : s.info();
			for (CharacterData.Row row : c.collections.getOrDefault(s.collection(), List.of())) {
				String name = row.texts.getOrDefault(s.label(), "").trim();
				if (name.isEmpty()) continue;
				if (!s.when().isBlank()) {
					double w = sc.number(coll, row, s.when());
					if (Double.isNaN(w) || w == 0) continue;
				}
				Map<String, Integer> cost = new LinkedHashMap<>();
				for (Map.Entry<String, String> e : s.cost().entrySet()) {
					double v = sc.number(coll, row, e.getValue());
					if (!Double.isNaN(v) && v > 0) cost.put(e.getKey(), (int) Math.round(v));
				}
				int level = 0;
				int left = -1;
				int max = 0;
				if (!s.slotStore().isEmpty() && !s.slotLevel().isEmpty()) {
					double lv = sc.number(coll, row, "row." + s.slotLevel());
					if (!Double.isNaN(lv)) level = (int) Math.round(lv);
					if (level >= 1) {
						double l = sc.number(s.slotStore() + level);
						double m = sc.number(s.slotStore() + level + "_max");
						if (!Double.isNaN(l) && !Double.isNaN(m)) {
							left = (int) Math.round(l);
							max = (int) Math.round(m);
						}
					}
				}
				boolean usable = myTurn && (left < 0 || left > 0);
				for (SheetFormat.Col ac : coll.columns) {
					if (ac.type.equals("activate") && ac.uses != null && row.values.getOrDefault(ac.uses, coll.column(ac.uses).def) <= 0) usable = false;
				}
				if (usable) {
					for (Map.Entry<String, Integer> e : cost.entrySet()) {
						if (resourceLeft(e.getKey()) < e.getValue()) usable = false;
					}
				}
				String group = "";
				int groupOrder = 0;
				SheetFormat.Col gc = s.group().isEmpty() ? null : coll.column(s.group());
				if (gc != null) {
					if (gc.type.equals("choice") && !gc.options.isEmpty()) {
						int idx = Math.floorMod((int) Math.round(row.values.getOrDefault(gc.id, gc.def)), gc.options.size());
						groupOrder = idx;
						group = idx < gc.groupLabels.size() ? gc.groupLabels.get(idx) : gc.options.get(idx);
					} else if (gc.isText()) {
						group = row.texts.getOrDefault(gc.id, "").trim();
					} else {
						int v = (int) Math.round(row.values.getOrDefault(gc.id, gc.def));
						groupOrder = v;
						group = gc.label + " " + v;
					}
				}
				String info = "";
				if (coll.column(infoCol) != null) info = row.texts.getOrDefault(infoCol, "").trim().replaceAll("\\s+", " ");
				String key = s.collection() + ":" + name;
				String mode = s.show().isEmpty() ? rules.showCost : s.show();
				out.add(new Slot(name, iconFor(s.icon()), level, cost, costText(cost, main, mode), left, max, usable,
						sc, coll, row, own && !c.remote, s.slotStore(), key, character, s.tray(), group, groupOrder, info,
						ActionBarConfig.pinned(character, key)));
			}
		}
		return new Built(out, rules.trays);
	}

	/** What is on the bar itself: every slot with no tray, then the entries pinned from the trays. */
	private static List<Slot> hotbarOf(List<Slot> all) {
		List<Slot> bar = new ArrayList<>();
		List<Slot> pinned = new ArrayList<>();
		for (Slot s : all) {
			if (s.tray().isEmpty()) bar.add(s);
			else if (s.pinned()) pinned.add(s);
		}
		pinned.sort(Comparator.comparingInt(s -> ActionBarConfig.pinOrder(s.character(), s.key())));
		bar.addAll(pinned);
		return bar;
	}

	private static int resourceLeft(String id) {
		for (CombatStatePayload.Res r : ClientCombatState.resources) if (r.id().equals(id)) return r.left();
		return 0;
	}

	/**
	 * What is written on the slot. "auto" stays quiet for the plain default (one of the main resource), so a D&D
	 * attack shows nothing while a bonus action shows "B"; "always" writes every cost, "never" none.
	 */
	static String costText(Map<String, Integer> cost, String main, String mode) {
		if (cost.isEmpty() || mode.equals("never")) return "";
		if (mode.equals("auto") && cost.size() == 1 && cost.getOrDefault(main, 0) == 1) return "";
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : cost.entrySet()) {
			if (sb.length() > 0) sb.append('+');
			String id = e.getKey();
			int n = e.getValue();
			if (id.equals(main)) {
				sb.append(n);
			} else {
				String initial = CombatHud.resourceLabel(id).substring(0, 1).toUpperCase();
				sb.append(n == 1 ? initial : n + initial);
			}
		}
		return sb.toString();
	}

	private static ItemStack iconFor(String id) {
		return ICONS.computeIfAbsent(id, k -> {
			Identifier ident = k.isEmpty() ? null : Identifier.tryParse(k);
			if (ident != null && Registries.ITEM.containsId(ident)) return new ItemStack(Registries.ITEM.get(ident));
			return new ItemStack(Items.PAPER);
		});
	}

	// ------------------------------------------------------------------ small drawing helpers

	private static int alpha(int argb, float factor) {
		int a = Math.round(((argb >>> 24) & 0xFF) * factor);
		return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF);
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	/** A rectangle with its four corner pixels cut. */
	private static void round(DrawContext g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + w, y + h - 1, color);
	}

	/** A rounded rectangle with a vertical gradient. */
	private static void roundGradient(DrawContext g, int x, int y, int w, int h, int top, int bottom) {
		g.fillGradient(x + 1, y, x + w - 1, y + h, top, bottom);
		g.fillGradient(x, y + 1, x + 1, y + h - 1, top, bottom);
		g.fillGradient(x + w - 1, y + 1, x + w, y + h - 1, top, bottom);
	}

	/** An outlined, gradient-filled rounded box. */
	private static void box(DrawContext g, int x, int y, int w, int h, int edge, int top, int bottom) {
		round(g, x, y, w, h, edge);
		roundGradient(g, x + 1, y + 1, w - 2, h - 2, top, bottom);
	}

	private static boolean inside(double mx, double my, int[] r) {
		return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	private static void hit(int x, int y, int w, int h, Runnable left, Runnable right, List<Text> tip) {
		HITS.add(new Hit(x, y, w, h, left, right, tip));
	}

	/** A 7 by 7 pip: a disc, or a diamond so two resources can be told apart without colour. */
	private static void drawShape(DrawContext ctx, int x, int y, boolean diamond, int color) {
		int[] widths = diamond ? new int[] {1, 3, 5, 7, 5, 3, 1} : new int[] {3, 5, 7, 7, 7, 5, 3};
		for (int row = 0; row < 7; row++) {
			int w = widths[row];
			int off = (7 - w) / 2;
			ctx.fill(x + off, y + row, x + off + w, y + row + 1, color);
		}
	}

	private static float flash(String key) {
		Long t = FLASH.get(key);
		if (t == null) return 0f;
		float age = (System.currentTimeMillis() - t) / 220f;
		if (age >= 1f) {
			FLASH.remove(key);
			return 0f;
		}
		return 1f - age;
	}

	// ------------------------------------------------------------------ rendering

	private static Text waitingText() {
		int idx = ClientCombatState.activeIndex;
		if (idx < 0 || idx >= ClientCombatState.entries.size()) return Text.empty();
		return Text.translatable("tacticalcombat.hud.waiting", CombatHud.nameOf(ClientCombatState.entries.get(idx)));
	}

	private static String buttonText(CombatStatePayload.MoveButton b) {
		return b.cost().isEmpty() ? b.label() : b.label() + " (" + b.cost() + ")";
	}

	public static void render(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW, int screenH) {
		ActionBarConfig.ensureLoaded();
		HITS.clear();
		barRect = null;
		popRect = null;

		boolean myTurn = ClientCombatState.canMoveActive();
		CombatStatePayload.Entry mine = mine(mc);
		Built built = buildSlots(mine, myTurn);
		hotbar = hotbarOf(built.all());
		lastTrays = built.trays();
		if (openTray >= lastTrays.size()) openTray = -1;
		float sc = ActionBarConfig.scale();
		lastScale = sc;
		int vw = Math.round(screenW / sc);
		int vh = Math.round(screenH / sc);
		double mxr = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double myr = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		double mx = mxr / sc;
		double my = myr / sc;
		float op = ActionBarConfig.opacity;
		TextRenderer f = font;

		// ---- what is on the bar
		boolean showBars = ActionBarConfig.bars && mine != null && !barsOf(mine).isEmpty();
		boolean showRes = myTurn && ActionBarConfig.resources && !ClientCombatState.resources.isEmpty();
		boolean showMove = myTurn && ActionBarConfig.movement;
		boolean planning = ClientCombatState.planning;
		boolean showText = !myTurn;
		int hpW = 0, hpH = 0, resW = 0, resH = 0, moveW = 0, moveH = 0, textW = 0, endW = 0;
		List<Integer> buttonW = new ArrayList<>();
		if (showBars) {
			int labelW = 0;
			for (CombatStatePayload.Bar b : barsOf(mine)) labelW = Math.max(labelW, f.getWidth(b.label()));
			hpW = labelW + 4 + 50 + 4 + f.getWidth("000 / 000");
			hpH = barsOf(mine).size() * ROW;
		}
		if (showRes) {
			for (CombatStatePayload.Res r : ClientCombatState.resources) {
				resW = Math.max(resW, f.getWidth(CombatHud.resourceLabel(r.id())) + 4 + Math.min(r.max(), 8) * 9);
			}
			resH = ClientCombatState.resources.size() * ROW;
		}
		if (showMove) {
			int btnTotal = 0;
			for (CombatStatePayload.MoveButton b : ClientCombatState.moveButtons) {
				int bw = f.getWidth(buttonText(b)) + 10;
				buttonW.add(bw);
				btnTotal += bw + 3;
			}
			moveW = Math.max(110, btnTotal - 3);
			moveH = ROW + 9 + (buttonW.isEmpty() ? 0 : 15);
		}
		Text waiting = Text.empty();
		if (showText) {
			waiting = planning
					? Text.translatable("tacticalcombat.hud.planning", TacticalCombatClient.ENCOUNTER_KEY.getBoundKeyLocalizedText())
					: waitingText();
			textW = f.getWidth(waiting) + 4;
		}
		if (myTurn) endW = Math.max(f.getWidth(Text.translatable("tacticalcombat.hud.end_turn_short")), 40) + 14;

		int trays = lastTrays.size();
		int cogW = 11;
		List<Integer> widths = new ArrayList<>();
		if (hpW > 0) widths.add(hpW);
		if (resW > 0) widths.add(resW);
		if (moveW > 0) widths.add(moveW);
		if (textW > 0) widths.add(textW);
		if (endW > 0) widths.add(endW);
		int fixed = PAD * 2 + cogW;
		for (int w : widths) fixed += w;
		int trayW = trays == 0 ? 0 : trays * (SLOT + GAP) - GAP;
		boolean haveSlotGroup = !hotbar.isEmpty() || trays > 0;
		int groups = widths.size() + (haveSlotGroup ? 1 : 0) + 1; // + the cog
		fixed += SEP * Math.max(0, groups - 1) + trayW + (trays > 0 && !hotbar.isEmpty() ? GAP + 2 : 0);
		int room = vw - 12 - fixed;
		int fit = Math.max(0, Math.min(MAX_SLOTS, (room + GAP) / (SLOT + GAP)));
		int visible = Math.min(hotbar.size(), fit);
		int hidden = hotbar.size() - visible;
		int slotsW = visible == 0 ? 0 : visible * (SLOT + GAP) - GAP;

		// nothing to show at all: skip the frame entirely
		if (widths.isEmpty() && !haveSlotGroup) return;

		int innerW = slotsW + (trays > 0 ? trayW + (visible > 0 ? GAP + 2 : 0) : 0);
		int total = PAD * 2 + cogW + SEP * Math.max(0, groups - 1) + innerW;
		for (int w : widths) total += w;
		int maxH = haveSlotGroup ? SLOT : 0;
		maxH = Math.max(maxH, Math.max(Math.max(hpH, resH), moveH));
		int barW = Math.max(total, 50);
		int barH = PAD * 2 + Math.max(maxH, ROW);
		int cxBar = ActionBarConfig.cx >= 0 ? Math.round(ActionBarConfig.cx / sc) : vw / 2;
		int bottom = ActionBarConfig.bottom >= 0 ? Math.round(ActionBarConfig.bottom / sc) : vh - MARGIN;
		int bx = Math.max(2, Math.min(vw - barW - 2, cxBar - barW / 2));
		int by = Math.max(2, Math.min(vh - barH - 2, bottom - barH));
		barRect = new int[] {bx, by, barW, barH};
		boolean popOver = inside(mx, my, popRectGuess);
		List<Text> tip = null;

		ctx.getMatrices().push();
		ctx.getMatrices().scale(sc, sc, 1f);

		// ---- frame
		ctx.fill(bx + 1, by + barH + 1, bx + barW + 1, by + barH + 3, alpha(0x60000000, op)); // shadow
		int edgeColor = myTurn ? mix(ACCENT, 0xFF000000, 0.15f + 0.1f * (float) Math.sin(System.currentTimeMillis() / 400.0)) : EDGE;
		round(ctx, bx - 1, by - 1, barW + 2, barH + 2, alpha(edgeColor, Math.min(1f, op + 0.1f)));
		roundGradient(ctx, bx, by, barW, barH, alpha(0xFF262B3A, op), alpha(0xFF171A23, op));
		ctx.fill(bx + 2, by + 1, bx + barW - 2, by + 2, alpha(0x30FFFFFF, op)); // top highlight

		int sepTop = by + 5;
		int sepBottom = by + barH - 5;
		int cx = bx + PAD;
		int mid = by + barH / 2;
		int trayX0 = cx;

		if (hpW > 0) {
			drawBars(ctx, f, cx, mid - hpH / 2, mine);
			cx += hpW;
			cx = sep(ctx, cx, sepTop, sepBottom);
		}
		if (resW > 0) {
			drawResources(ctx, f, cx, mid - resH / 2);
			cx += resW;
			cx = sep(ctx, cx, sepTop, sepBottom);
		}
		if (moveW > 0) {
			drawMove(ctx, f, cx, mid - moveH / 2, moveW, buttonW, mx, my, popOver);
			cx += moveW;
			cx = sep(ctx, cx, sepTop, sepBottom);
		}
		if (textW > 0) {
			ctx.drawTextWithShadow(f, waiting, cx + 2, mid - 4, MUTED);
			cx += textW;
			if (haveSlotGroup || endW > 0) cx = sep(ctx, cx, sepTop, sepBottom);
		}

		// ---- slots and trays
		for (int i = 0; i < visible; i++) {
			Slot s = hotbar.get(i);
			int sx = cx + i * (SLOT + GAP);
			int sy = mid - SLOT / 2;
			boolean over = !popOver && inside(mx, my, new int[] {sx, sy, SLOT, SLOT});
			drawSlot(ctx, f, sx, sy, i, s, over);
			final Slot slot = s;
			hit(sx, sy, SLOT, SLOT, () -> use(slot, false), () -> use(slot, true), slotTip(f, slot, false));
			if (over) tip = slotTip(f, slot, false);
		}
		cx += slotsW;
		if (hidden > 0 && slotsW > 0) {
			String more = "+" + hidden;
			ctx.drawTextWithShadow(f, more, cx - f.getWidth(more), by - 10, MUTED);
		}
		if (trays > 0) {
			if (visible > 0) {
				cx += GAP;
				ctx.fill(cx, sepTop + 2, cx + 1, sepBottom - 2, alpha(EDGE, op));
				cx += 2;
			}
			trayX0 = cx;
			for (int t = 0; t < trays; t++) {
				int tx = cx + t * (SLOT + GAP);
				int ty = mid - SLOT / 2;
				CombatRules.Tray tray = lastTrays.get(t);
				int count = 0;
				for (Slot s : built.all()) if (s.tray().equals(tray.id())) count++;
				boolean over = !popOver && inside(mx, my, new int[] {tx, ty, SLOT, SLOT});
				drawTrayButton(ctx, f, tx, ty, tray, count, t == openTray, over);
				final int index = t;
				List<Text> tt = new ArrayList<>();
				tt.add(Text.literal(tray.label()));
				tt.add(Text.literal(count + (count == 1 ? " entry" : " entries")).formatted(Formatting.GRAY));
				tt.add(Text.literal("Click or press T to " + (t == openTray ? "close" : "open")).formatted(Formatting.GRAY));
				hit(tx, ty, SLOT, SLOT, () -> toggleTray(index), null, tt);
				if (over) tip = tt;
			}
			cx += trayW;
		}
		if (haveSlotGroup) cx = sep(ctx, cx, sepTop, sepBottom);

		// ---- end turn
		if (endW > 0) {
			int ex = cx;
			int ey = mid - SLOT / 2;
			boolean over = !popOver && inside(mx, my, new int[] {ex, ey, endW, SLOT});
			box(ctx, ex, ey, endW, SLOT, over ? 0xFFD05A66 : 0xFF6E1F29, over ? 0xFFB63A47 : 0xFF9A2F3B, over ? 0xFF8E2A35 : 0xFF6E1F29);
			ctx.drawCenteredTextWithShadow(f, Text.translatable("tacticalcombat.hud.end_turn_short"), ex + endW / 2, ey + 4, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(f, TacticalCombatClient.END_TURN_KEY.getBoundKeyLocalizedText(), ex + endW / 2, ey + 13, 0xFFE9B4BA);
			hit(ex, ey, endW, SLOT, () -> {
				if (ClientCombatState.isMyTurn()) TacticalCombatClient.sendEndTurn();
				else ClientPlayNetworking.send(new EncounterActionPayload(6, 0, 0)); // the Dungeon Master ends the creature's turn
			}, null, null);
			cx += endW;
			cx += SEP;
		}

		// ---- settings button: three dots
		int gx = bx + barW - PAD - cogW;
		int gy = mid - 6;
		boolean gOver = !popOver && inside(mx, my, new int[] {gx, gy, cogW, 12});
		round(ctx, gx, gy, cogW, 12, settingsOpen ? ACCENT : gOver ? EDGE_LIGHT : EDGE);
		round(ctx, gx + 1, gy + 1, cogW - 2, 10, gOver ? 0xFF343A4C : 0xFF1F2330);
		for (int d = 0; d < 3; d++) ctx.fill(gx + 2 + d * 3, gy + 5, gx + 4 + d * 3, gy + 7, settingsOpen ? ACCENT : MUTED);
		List<Text> gtip = List.of(Text.literal("Action bar settings"), Text.literal("Size, opacity, what to show").formatted(Formatting.GRAY));
		hit(gx, gy, cogW, 12, () -> {
			settingsOpen = !settingsOpen;
			openTray = -1;
		}, null, gtip);
		if (gOver) tip = gtip;

		// ---- pop-ups
		int[] pop = null;
		if (openTray >= 0 && openTray < lastTrays.size()) {
			CombatRules.Tray tray = lastTrays.get(openTray);
			List<Slot> entries = new ArrayList<>();
			for (Slot s : built.all()) if (s.tray().equals(tray.id())) entries.add(s);
			int cxTray = trayX0 + openTray * (SLOT + GAP) + SLOT / 2;
			pop = drawTray(ctx, f, tray, entries, cxTray, barRect, vw, vh, mx, my);
			tip = trayTip != null ? trayTip : tip;
		} else if (settingsOpen) {
			pop = drawSettings(ctx, f, gx + cogW, barRect, vw, vh, mx, my);
		}
		popRect = pop;
		popRectGuess = pop;
		ctx.getMatrices().pop();

		if (tip == null && !dragging && inside(mx, my, barRect) && !popOver && !overControl(mx, my)) {
			tip = List.of(Text.translatable("tacticalcombat.bar.drag"));
		}
		if (tip != null) ctx.drawTooltip(font, tip, (int) mxr, (int) myr);
	}

	/** Popup rectangle of the previous frame (hover state of the bar is decided before the popup is drawn). */
	private static int[] popRectGuess;
	private static List<Text> trayTip;

	private static int sep(DrawContext ctx, int x, int top, int bottom) {
		ctx.fill(x + SEP / 2, top, x + SEP / 2 + 1, bottom, EDGE);
		ctx.fill(x + SEP / 2 + 1, top, x + SEP / 2 + 2, bottom, 0x20FFFFFF);
		return x + SEP;
	}

	private static boolean overControl(double mx, double my) {
		for (Hit h : HITS) if (h.at(mx, my)) return true;
		return false;
	}

	private static void drawBars(DrawContext ctx, TextRenderer font, int x, int y0, CombatStatePayload.Entry mine) {
		int labelW = 0;
		for (CombatStatePayload.Bar b : barsOf(mine)) labelW = Math.max(labelW, font.getWidth(b.label()));
		int barW = 50;
		int y = y0;
		for (CombatStatePayload.Bar b : barsOf(mine)) {
			ctx.drawTextWithShadow(font, b.label(), x, y + 2, TEXT);
			int bx = x + labelW + 4;
			float frac = b.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, b.now() / b.max()));
			round(ctx, bx - 1, y + 1, barW + 2, 10, 0xFF05060A);
			ctx.fill(bx, y + 2, bx + barW, y + 10, 0xFF1A1D26);
			int w = Math.round(barW * frac);
			if (w > 0) {
				ctx.fillGradient(bx, y + 2, bx + w, y + 10, mix(b.color(), 0xFFFFFFFF, 0.28f), mix(b.color(), 0xFF000000, 0.25f));
				ctx.fill(bx, y + 2, bx + w, y + 3, 0x33FFFFFF);
			}
			ctx.drawTextWithShadow(font, Math.round(b.now()) + " / " + Math.round(b.max()), bx + barW + 4, y + 2, 0xFFFFFFFF);
			y += ROW;
		}
	}

	private static void drawResources(DrawContext ctx, TextRenderer font, int x0, int y0) {
		int y = y0;
		for (CombatStatePayload.Res r : ClientCombatState.resources) {
			String label = CombatHud.resourceLabel(r.id());
			ctx.drawTextWithShadow(font, label, x0, y + 2, MUTED);
			int x = x0 + font.getWidth(label) + 4;
			boolean diamond = r.id().equals("bonus");
			int color = switch (r.id()) {
				case "action" -> ACTION;
				case "bonus" -> BONUS;
				default -> OTHER;
			};
			int pips = Math.min(r.max(), 8);
			for (int i = 0; i < pips; i++) {
				boolean full = i < r.left();
				drawShape(ctx, x, y + 2, diamond, full ? color : SPENT);
				if (full) ctx.fill(x + 2, y + 3, x + 4, y + 4, 0x66FFFFFF); // a little shine
				x += 9;
			}
			y += ROW;
		}
	}

	private static void drawMove(DrawContext ctx, TextRenderer font, int x, int y, int w, List<Integer> buttonW,
								 double mx, double my, boolean popOver) {
		float budget = Math.max(0.001f, ClientCombatState.moveBudget);
		float left = Math.max(0f, budget - ClientCombatState.moveUsed);
		float sq = ClientCombatState.unit.isEmpty() ? 1f : ClientCombatState.square;
		String unit = ClientCombatState.unit.isEmpty() ? "" : " " + ClientCombatState.unit;
		ctx.drawTextWithShadow(font, Text.translatable("tacticalcombat.hud.movement"), x, y + 1, MUTED);
		String nums = String.format("%.0f / %.0f%s", left * sq, budget * sq, unit);
		ctx.drawTextWithShadow(font, nums, x + w - font.getWidth(nums), y + 1, TEXT);

		int by = y + ROW + 1;
		round(ctx, x - 1, by - 1, w + 2, 8, 0xFF05060A);
		ctx.fill(x, by, x + w, by + 6, 0xFF14161E);
		int fw = Math.round(w * (left / budget));
		if (fw > 0) {
			ctx.fillGradient(x, by, x + fw, by + 6, mix(MOVE, 0xFFFFFFFF, 0.25f), mix(MOVE, 0xFF000000, 0.2f));
			ctx.fill(x, by, x + fw, by + 1, 0x33FFFFFF);
		}
		if (budget <= 12 && w / budget >= 4) { // one tick per grid square
			for (int i = 1; i < Math.round(budget); i++) {
				int tx = x + Math.round(w * (i / budget));
				ctx.fill(tx, by, tx + 1, by + 6, 0xFF0F1117);
			}
		}

		int bx = x;
		int byy = by + 10;
		for (int i = 0; i < buttonW.size(); i++) {
			CombatStatePayload.MoveButton b = ClientCombatState.moveButtons.get(i);
			int bw = buttonW.get(i);
			boolean over = b.enabled() && !popOver && inside(mx, my, new int[] {bx, byy, bw, 13});
			box(ctx, bx, byy, bw, 13, over ? EDGE_LIGHT : EDGE, !b.enabled() ? 0xFF1A1D26 : over ? 0xFF3A4156 : SLOT_TOP,
					!b.enabled() ? 0xFF161922 : over ? 0xFF2A3042 : SLOT_BOTTOM);
			ctx.drawTextWithShadow(font, buttonText(b), bx + 5, byy + 3, b.enabled() ? TEXT : DIM);
			final CombatStatePayload.MoveButton move = b;
			hit(bx, byy, bw, 13, () -> {
				if (move.enabled() && ClientCombatState.canMoveActive()) {
					ClientPlayNetworking.send(new dev.tacticalcombat.net.BuyMovePayload(move.id()));
				}
			}, null, null);
			bx += bw + 3;
		}
	}

	private static void drawSlot(DrawContext ctx, TextRenderer font, int x, int y0, int index, Slot s, boolean over) {
		boolean lift = over && s.usable();
		int y = lift ? y0 - 1 : y0;
		int edge = lift ? mix(ACCENT, 0xFF000000, 0.25f) : over ? EDGE_LIGHT : EDGE;
		box(ctx, x, y, SLOT, SLOT, edge, lift ? 0xFF3A4156 : SLOT_TOP, lift ? 0xFF283044 : SLOT_BOTTOM);
		ctx.drawItem(s.icon(), x + (SLOT - 16) / 2, y + (SLOT - 16) / 2);
		if (!s.usable()) ctx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xA00E1016);
		float fl = flash(s.key());
		if (fl > 0f) ctx.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, ((int) (fl * 150) << 24) | 0xFFFFFF);

		// small corner texts at half size: key, level, cost
		var m = ctx.getMatrices();
		m.push();
		m.scale(0.5f, 0.5f, 1f);
		if (ActionBarConfig.keys) ctx.drawText(font, String.valueOf(index + 1), (x + 3) * 2, (y + 2) * 2, 0xFFB8BECC, false);
		if (s.level() >= 1) {
			String lv = String.valueOf(s.level());
			ctx.drawText(font, lv, (x + SLOT - 3) * 2 - font.getWidth(lv), (y + 2) * 2, 0xFF9FA9F0, false);
		}
		if (!s.costText().isEmpty()) {
			int tw = font.getWidth(s.costText());
			ctx.fill((x + SLOT - 3) * 2 - tw - 2, (y + SLOT - 8) * 2 - 1, (x + SLOT - 2) * 2, (y + SLOT - 2) * 2, 0xCC0E1016);
			ctx.drawText(font, s.costText(), (x + SLOT - 3) * 2 - tw, (y + SLOT - 7) * 2, ACCENT, false);
		}
		m.pop();

		// spell slots left, one dot each, along the bottom edge
		if (s.max() > 0) {
			int dots = Math.min(s.max(), 6);
			for (int i = 0; i < dots; i++) {
				int dx = x + 4 + i * 3;
				int dy = y + SLOT - 4;
				ctx.fill(dx, dy, dx + 2, dy + 2, i < s.left() ? 0xFF8FA0F0 : 0xFF3A3F55);
			}
		}
		if (s.pinned() && !s.tray().isEmpty()) ctx.fill(x + 2, y + 2, x + 4, y + 4, ACCENT); // a pinned tray entry
	}

	private static void drawTrayButton(DrawContext ctx, TextRenderer font, int x, int y, CombatRules.Tray tray, int count, boolean open, boolean over) {
		box(ctx, x, y, SLOT, SLOT, open ? ACCENT : over ? EDGE_LIGHT : EDGE, open ? 0xFF4A3B16 : over ? 0xFF3A4156 : SLOT_TOP,
				open ? 0xFF2E2410 : over ? 0xFF283044 : SLOT_BOTTOM);
		ctx.drawItem(iconFor(tray.icon().isEmpty() ? "minecraft:bundle" : tray.icon()), x + (SLOT - 16) / 2, y + (SLOT - 16) / 2 - 1);
		var m = ctx.getMatrices();
		m.push();
		m.scale(0.5f, 0.5f, 1f);
		ctx.drawText(font, String.valueOf(count), (x + 3) * 2, (y + SLOT - 7) * 2, open ? ACCENT : 0xFFB8BECC, false);
		ctx.drawText(font, "T", (x + SLOT - 3) * 2 - font.getWidth("T"), (y + 2) * 2, 0xFFB8BECC, false);
		m.pop();
		// a little tab mark under the icon says "this opens a list"
		ctx.fill(x + SLOT / 2 - 3, y + SLOT - 3, x + SLOT / 2 + 3, y + SLOT - 2, open ? ACCENT : 0xFF7C8394);
	}

	// ------------------------------------------------------------------ tooltips

	private static List<String> wrap(TextRenderer font, String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && font.getWidth(line + " " + word) > width) {
				lines.add(line.toString());
				line.setLength(0);
				if (lines.size() >= 4) break;
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0 && lines.size() < 4) lines.add(line.toString());
		return lines;
	}

	private static List<Text> slotTip(TextRenderer font, Slot s, boolean fromTray) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal(s.name()).formatted(Formatting.WHITE));
		if (s.level() >= 1) {
			String slots = s.max() > 0 ? " (" + s.left() + "/" + s.max() + ")" : "";
			lines.add(Text.translatable("tacticalcombat.bar.level", s.level()).append(slots));
		}
		if (!s.cost().isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, Integer> e : s.cost().entrySet()) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(e.getValue()).append(' ').append(CombatHud.resourceLabel(e.getKey()));
			}
			lines.add(Text.translatable("tacticalcombat.bar.cost", sb.toString()));
		}
		if (!s.info().isEmpty()) {
			for (String l : wrap(font, s.info(), 190)) lines.add(Text.literal(l).formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
		}
		lines.add(Text.translatable("tacticalcombat.bar.use").formatted(Formatting.GRAY));
		if (fromTray) lines.add(Text.literal(s.pinned() ? "Star: take it off the bar" : "Star: keep it on the bar").formatted(Formatting.GRAY));
		if (!s.usable()) lines.add(Text.translatable("tacticalcombat.bar.unavailable"));
		return lines;
	}

	// ------------------------------------------------------------------ the tray pop-up

	private static final int POP_W = 214;
	private static final int ENTRY_H = 22;
	private static final int HEAD_H = 13;

	/** A heading (slot == null) or an entry of the open tray. */
	private record TrayRow(String heading, int left, int max, Slot slot) {
		int height() {
			return slot == null ? HEAD_H : ENTRY_H;
		}
	}

	private static List<TrayRow> trayRows(List<Slot> entries) {
		List<Slot> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator.comparingInt((Slot s) -> s.groupOrder()).thenComparing(Slot::group, String.CASE_INSENSITIVE_ORDER));
		List<TrayRow> rows = new ArrayList<>();
		String last = null;
		for (Slot s : sorted) {
			if (!s.group().isEmpty() && !s.group().equals(last)) {
				rows.add(new TrayRow(s.group(), s.level() >= 1 ? s.left() : -1, s.level() >= 1 ? s.max() : 0, null));
				last = s.group();
			}
			rows.add(new TrayRow("", 0, 0, s));
		}
		return rows;
	}

	private static int[] drawTray(DrawContext ctx, TextRenderer font, CombatRules.Tray tray, List<Slot> entries, int anchorX,
								  int[] bar, int vw, int vh, double mx, double my) {
		trayTip = null;
		List<TrayRow> rows = trayRows(entries);
		int contentH = 0;
		for (TrayRow r : rows) contentH += r.height();
		int headerH = 17;
		int above = bar[1] - 6;
		int below = vh - (bar[1] + bar[3]) - 6;
		boolean up = above >= 90 || above >= below;
		int avail = Math.max(60, (up ? above : below) - 4);
		int bodyH = Math.min(Math.max(contentH, ENTRY_H), avail - headerH);
		int h = headerH + bodyH + 4;
		int w = POP_W;
		int x = Math.max(4, Math.min(vw - w - 4, anchorX - w / 2));
		int y = up ? bar[1] - 6 - h : bar[1] + bar[3] + 6;
		int[] rect = {x, y, w, h};

		box(ctx, x, y, w, h, EDGE_LIGHT, 0xFF222736, 0xFF171A23);
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + headerH, 0xFF4A3B16, 0xFF2E2410);
		ctx.fill(x + 1, y + headerH, x + w - 1, y + headerH + 1, 0xFF05060A);
		ctx.drawItem(iconFor(tray.icon().isEmpty() ? "minecraft:bundle" : tray.icon()), x + 2, y + 1);
		ctx.drawTextWithShadow(font, tray.label() + "  (" + entries.size() + ")", x + 20, y + 5, 0xFFFFFFFF);
		// close
		int cx = x + w - 13;
		boolean cOver = inside(mx, my, new int[] {cx, y + 3, 10, 10});
		round(ctx, cx, y + 3, 10, 10, cOver ? 0xFFB8323F : 0xFF5A2229);
		ctx.drawCenteredTextWithShadow(font, "x", cx + 5, y + 4, 0xFFFFFFFF);
		hit(cx, y + 3, 10, 10, () -> openTray = -1, null, List.of(Text.literal("Close (Esc)")));
		hit(x, y, w, h, () -> {}, () -> {}, null); // the pop-up swallows clicks on its background

		if (rows.isEmpty()) {
			ctx.drawTextWithShadow(font, "Nothing here yet.", x + 8, y + headerH + 8, DIM);
			return rect;
		}

		// how many rows fit, and where the list may start so the last row is visible
		int maxStart = 0;
		int acc = 0;
		for (int i = rows.size() - 1; i >= 0; i--) {
			acc += rows.get(i).height();
			if (acc > bodyH) {
				maxStart = i + 1;
				break;
			}
		}
		trayScroll = Math.max(0, Math.min(trayScroll, maxStart));
		int ry = y + headerH + 3;
		int bottomLimit = y + headerH + 3 + bodyH;
		int shown = 0;
		for (int i = trayScroll; i < rows.size(); i++) {
			TrayRow r = rows.get(i);
			if (ry + r.height() > bottomLimit) break;
			if (r.slot() == null) {
				ctx.drawTextWithShadow(font, r.heading(), x + 8, ry + 2, ACCENT);
				int tw = font.getWidth(r.heading());
				ctx.fill(x + 12 + tw, ry + 6, x + w - 22, ry + 7, 0xFF3A3F4D);
				if (r.max() > 0) { // slots left of that level, as dots at the right
					int dots = Math.min(r.max(), 8);
					for (int d = 0; d < dots; d++) {
						int dx = x + w - 10 - (dots - d) * 4;
						ctx.fill(dx, ry + 3, dx + 3, ry + 6, d < r.left() ? 0xFF8FA0F0 : 0xFF3A3F55);
					}
				}
			} else {
				drawTrayEntry(ctx, font, r.slot(), x + 4, ry, w - 12, mx, my);
			}
			ry += r.height();
			shown++;
		}
		if (maxStart > 0) { // scroll bar
			int track = bodyH;
			int barH = Math.max(10, Math.round(track * (shown / (float) rows.size())));
			int barY = y + headerH + 3 + Math.round((track - barH) * (trayScroll / (float) maxStart));
			ctx.fill(x + w - 5, y + headerH + 3, x + w - 3, y + headerH + 3 + track, 0xFF0F1117);
			ctx.fill(x + w - 5, barY, x + w - 3, barY + barH, 0xFF6B7184);
		}
		return rect;
	}

	private static void drawTrayEntry(DrawContext ctx, TextRenderer font, Slot s, int x, int y, int w, double mx, double my) {
		boolean over = inside(mx, my, new int[] {x, y, w, ENTRY_H - 1});
		box(ctx, x, y, w, ENTRY_H - 1, over ? EDGE_LIGHT : 0xFF2A2F3C, over ? 0xFF303749 : 0xFF222736, over ? 0xFF262C3C : 0xFF1B1F2B);
		ctx.drawItem(s.icon(), x + 3, y + 2);
		if (!s.usable()) ctx.fill(x + 1, y + 1, x + w - 1, y + ENTRY_H - 2, 0x90101218);
		float fl = flash(s.key());
		if (fl > 0f) ctx.fill(x + 1, y + 1, x + w - 1, y + ENTRY_H - 2, ((int) (fl * 130) << 24) | 0xFFFFFF);

		int right = x + w - 4;
		// pin star at the far right
		int px = right - 9;
		int py = y + (ENTRY_H - 1) / 2 - 4;
		boolean pOver = inside(mx, my, new int[] {px - 2, py - 2, 13, 13});
		drawShape(ctx, px, py, true, s.pinned() ? ACCENT : pOver ? 0xFF9AA0AE : 0xFF4A5062);
		if (s.pinned()) ctx.fill(px + 3, py + 2, px + 4, py + 3, 0x88FFFFFF);
		final Slot slot = s;
		hit(px - 2, py - 2, 13, 13, () -> ActionBarConfig.togglePin(slot.character(), slot.key()), null,
				List.of(Text.literal(s.pinned() ? "Take it off the bar" : "Keep it on the bar").formatted(Formatting.WHITE),
						Text.literal("Pinned entries get a number key").formatted(Formatting.GRAY)));
		right = px - 4;
		// cost, spell slots
		if (!s.costText().isEmpty()) {
			int tw = font.getWidth(s.costText());
			ctx.drawText(font, s.costText(), right - tw, y + 7, ACCENT, false);
			right -= tw + 5;
		}
		if (s.level() >= 1) {
			String lv = "L" + s.level();
			int tw = font.getWidth(lv);
			ctx.drawText(font, lv, right - tw, y + 7, 0xFF9FA9F0, false);
			right -= tw + 5;
		}
		String name = font.trimToWidth(s.name(), Math.max(20, right - (x + 24)));
		ctx.drawText(font, name, x + 24, y + 7, s.usable() ? TEXT : DIM, false);
		hit(x, y, px - 2 - x, ENTRY_H - 1, () -> use(slot, false), () -> use(slot, true), slotTip(font, s, true));
		if (over && !pOver) trayTip = slotTip(font, s, true);
		else if (pOver) trayTip = List.of(Text.literal(s.pinned() ? "Take it off the bar" : "Keep it on the bar").formatted(Formatting.WHITE),
				Text.literal("Pinned entries get a number key").formatted(Formatting.GRAY));
	}

	// ------------------------------------------------------------------ the settings pop-up

	private static int[] drawSettings(DrawContext ctx, TextRenderer font, int anchorRight, int[] bar, int vw, int vh, double mx, double my) {
		trayTip = null;
		int w = 158;
		int rowH = 15;
		int rows = 7;
		int headerH = 17;
		int h = headerH + rows * rowH + 22;
		int x = Math.max(4, Math.min(vw - w - 4, anchorRight - w));
		boolean up = bar[1] - 6 - h >= 2;
		int y = up ? bar[1] - 6 - h : bar[1] + bar[3] + 6;
		int[] rect = {x, y, w, h};

		box(ctx, x, y, w, h, EDGE_LIGHT, 0xFF222736, 0xFF171A23);
		ctx.fillGradient(x + 1, y + 1, x + w - 1, y + headerH, 0xFF2F5C8A, 0xFF18304A);
		ctx.fill(x + 1, y + headerH, x + w - 1, y + headerH + 1, 0xFF05060A);
		ctx.drawTextWithShadow(font, "Action bar", x + 8, y + 5, 0xFFFFFFFF);
		int cx = x + w - 13;
		boolean cOver = inside(mx, my, new int[] {cx, y + 3, 10, 10});
		round(ctx, cx, y + 3, 10, 10, cOver ? 0xFFB8323F : 0xFF5A2229);
		ctx.drawCenteredTextWithShadow(font, "x", cx + 5, y + 4, 0xFFFFFFFF);
		hit(cx, y + 3, 10, 10, () -> settingsOpen = false, null, List.of(Text.literal("Close (Esc)")));
		hit(x, y, w, h, () -> {}, () -> {}, null);

		int ry = y + headerH + 5;
		// size
		stepper(ctx, font, x + 8, ry, w - 16, "Size", Math.round(ActionBarConfig.scale() * 100) + "%", mx, my,
				() -> ActionBarConfig.scaleIndex = Math.max(0, ActionBarConfig.scaleIndex - 1),
				() -> ActionBarConfig.scaleIndex = Math.min(ActionBarConfig.SCALES.length - 1, ActionBarConfig.scaleIndex + 1));
		ry += rowH;
		stepper(ctx, font, x + 8, ry, w - 16, "Opacity", Math.round(ActionBarConfig.opacity * 100) + "%", mx, my,
				() -> ActionBarConfig.opacity = Math.max(0.4f, Math.round((ActionBarConfig.opacity - 0.1f) * 10) / 10f),
				() -> ActionBarConfig.opacity = Math.min(1f, Math.round((ActionBarConfig.opacity + 0.1f) * 10) / 10f));
		ry += rowH + 2;
		check(ctx, font, x + 8, ry, w - 16, "Health bars", ActionBarConfig.bars, mx, my, () -> ActionBarConfig.bars = !ActionBarConfig.bars);
		ry += rowH;
		check(ctx, font, x + 8, ry, w - 16, "Resources", ActionBarConfig.resources, mx, my, () -> ActionBarConfig.resources = !ActionBarConfig.resources);
		ry += rowH;
		check(ctx, font, x + 8, ry, w - 16, "Movement", ActionBarConfig.movement, mx, my, () -> ActionBarConfig.movement = !ActionBarConfig.movement);
		ry += rowH;
		check(ctx, font, x + 8, ry, w - 16, "Key numbers", ActionBarConfig.keys, mx, my, () -> ActionBarConfig.keys = !ActionBarConfig.keys);
		ry += rowH + 3;
		int bw = w - 16;
		boolean rOver = inside(mx, my, new int[] {x + 8, ry, bw, 13});
		box(ctx, x + 8, ry, bw, 13, rOver ? EDGE_LIGHT : EDGE, rOver ? 0xFF3A4156 : SLOT_TOP, rOver ? 0xFF2A3042 : SLOT_BOTTOM);
		ctx.drawCenteredTextWithShadow(font, "Reset position and look", x + 8 + bw / 2, ry + 3, TEXT);
		hit(x + 8, ry, bw, 13, ActionBarConfig::resetLook, null, List.of(Text.literal("Back to the default place and look")));
		return rect;
	}

	/** A label with - value + : the actions change the setting, then it is saved. */
	private static void stepper(DrawContext ctx, TextRenderer font, int x, int y, int w, String label, String value, double mx, double my,
								Runnable minus, Runnable plus) {
		ctx.drawTextWithShadow(font, label, x, y + 2, MUTED);
		int px = x + w - 12;
		int vx = px - 4 - 30;
		int mxp = vx - 14;
		for (int i = 0; i < 2; i++) {
			int bx = i == 0 ? mxp : px;
			boolean over = inside(mx, my, new int[] {bx, y, 12, 12});
			box(ctx, bx, y, 12, 12, over ? EDGE_LIGHT : EDGE, over ? 0xFF3A4156 : SLOT_TOP, over ? 0xFF2A3042 : SLOT_BOTTOM);
			ctx.fill(bx + 3, y + 5, bx + 9, y + 7, TEXT);
			if (i == 1) ctx.fill(bx + 5, y + 3, bx + 7, y + 9, TEXT);
			final Runnable action = i == 0 ? minus : plus;
			hit(bx, y, 12, 12, () -> {
				action.run();
				ActionBarConfig.save();
			}, null, null);
		}
		ctx.drawCenteredTextWithShadow(font, value, vx + 15 - 1, y + 2, TEXT);
	}

	private static void check(DrawContext ctx, TextRenderer font, int x, int y, int w, String label, boolean on, double mx, double my, Runnable toggle) {
		boolean over = inside(mx, my, new int[] {x, y, w, 13});
		if (over) round(ctx, x - 2, y - 1, w + 4, 14, 0x30FFFFFF);
		round(ctx, x, y + 1, 10, 10, on ? ACCENT : EDGE);
		round(ctx, x + 1, y + 2, 8, 8, on ? 0xFF6B4E12 : 0xFF0F1117);
		if (on) ctx.fill(x + 3, y + 4, x + 7, y + 8, ACCENT);
		ctx.drawTextWithShadow(font, label, x + 16, y + 2, on ? TEXT : MUTED);
		hit(x, y, w, 13, () -> {
			toggle.run();
			ActionBarConfig.save();
		}, null, null);
	}

	// ------------------------------------------------------------------ input

	private static void toggleTray(int index) {
		openTray = openTray == index ? -1 : index;
		settingsOpen = false;
		trayScroll = 0;
	}

	/** True when the pointer is over the bar or its open pop-up (the battlefield underneath must not react). */
	public static boolean contains(double mx, double my, int screenW, int screenH) {
		if (!ClientCombatState.active) return false;
		double vx = mx / lastScale;
		double vy = my / lastScale;
		if (barRect != null && vx >= barRect[0] - 1 && vx < barRect[0] + barRect[2] + 1 && vy >= barRect[1] - 1 && vy < barRect[1] + barRect[3] + 1) return true;
		return popRect != null && inside(vx, vy, popRect);
	}

	/**
	 * Handles a click on the bar or its pop-up; true when it landed on it. Left click on a control uses it; left click
	 * on the bar's background starts dragging it; right click on the background puts it back in its default place. A
	 * click anywhere else closes an open pop-up (and goes on to the battlefield).
	 */
	public static boolean mouseClicked(double mxr, double myr, int button, int screenW, int screenH) {
		if (!ClientCombatState.active) return false;
		double mx = mxr / lastScale;
		double my = myr / lastScale;
		for (int i = HITS.size() - 1; i >= 0; i--) {
			Hit h = HITS.get(i);
			if (!h.at(mx, my)) continue;
			Runnable action = button == 0 ? h.left() : button == 1 ? h.right() : null;
			if (action != null) action.run();
			return true;
		}
		boolean onBar = barRect != null && mx >= barRect[0] - 1 && mx < barRect[0] + barRect[2] + 1
				&& my >= barRect[1] - 1 && my < barRect[1] + barRect[3] + 1;
		if (!onBar) {
			if (openTray >= 0 || settingsOpen) {
				openTray = -1;
				settingsOpen = false;
			}
			return false;
		}
		if (button == 1) {
			ActionBarConfig.cx = -1;
			ActionBarConfig.bottom = -1;
			ActionBarConfig.save();
			return true;
		}
		if (button == 0) dragging = true;
		return true;
	}

	/** Moves the bar while its background is dragged; true when a drag is in progress. */
	public static boolean mouseDragged(double dx, double dy, int screenW, int screenH) {
		if (!dragging || barRect == null) return false;
		if (ActionBarConfig.cx < 0) {
			ActionBarConfig.cx = Math.round((barRect[0] + barRect[2] / 2f) * lastScale);
			ActionBarConfig.bottom = Math.round((barRect[1] + barRect[3]) * lastScale);
		}
		ActionBarConfig.cx += (int) Math.round(dx);
		ActionBarConfig.bottom += (int) Math.round(dy);
		return true;
	}

	public static boolean mouseReleased() {
		boolean was = dragging;
		dragging = false;
		if (was) ActionBarConfig.save();
		return was;
	}

	/** The wheel over the open tray scrolls it. */
	public static boolean mouseScrolled(double mxr, double myr, double amount) {
		if (openTray < 0 || popRect == null) return false;
		if (!inside(mxr / lastScale, myr / lastScale, popRect)) return false;
		trayScroll = Math.max(0, trayScroll - (int) Math.signum(amount));
		return true;
	}

	/** Number keys pick the matching slot of the bar, T opens the tray (again: the next one), Esc closes a pop-up. */
	public static boolean keyPressed(int keyCode) {
		if (!ClientCombatState.active) return false;
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && (openTray >= 0 || settingsOpen)) {
			openTray = -1;
			settingsOpen = false;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_T && !lastTrays.isEmpty()) {
			openTray = openTray + 1 >= lastTrays.size() ? -1 : openTray + 1;
			settingsOpen = false;
			trayScroll = 0;
			return true;
		}
		if (!ClientCombatState.canMoveActive()) return false;
		if (keyCode < GLFW.GLFW_KEY_1 || keyCode > GLFW.GLFW_KEY_9) return false;
		int index = keyCode - GLFW.GLFW_KEY_1;
		if (index >= hotbar.size() || index >= MAX_SLOTS) return false;
		use(hotbar.get(index), false);
		return true;
	}

	/**
	 * Uses a slot. Left click: its attack roll (aimed at your target; the cost is spent and a spell slot used), or its
	 * dice when it has no attack. Right click: its damage dice, which cost nothing.
	 */
	private static void use(Slot s, boolean damage) {
		if (!ClientCombatState.canMoveActive()) return;
		// a greyed slot (its cost is paid, its spell slots gone) can not attack any more, but its damage is free: the
		// attack roll that just spent the action must still be followed by a damage roll
		if (!s.usable() && !damage) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		if (!ClientPlayNetworking.canSend(DiceRequestPayload.ID)) return;

		// a feature that is used rather than rolled (Second Wind): its cost, one of its uses, and its rules
		if (!damage && activates(s.coll(), s.row())) {
			boolean hasAttack = false;
			for (SheetFormat.Col col : s.coll().columns) if (col.type.equals("roll") && col.roll != null) hasAttack = true;
			if (!hasAttack) {
				CharacterSheetScreen.activate(s.sc(), s.coll(), s.row(), s.own());
				FLASH.put(s.key(), System.currentTimeMillis());
				return;
			}
		}
		SheetFormat.Col attack = null;
		SheetFormat.Col dice = null;
		String diceFormula = null;
		for (SheetFormat.Col col : s.coll().columns) {
			if (col.enabled != null) {
				double on = s.sc().number(s.coll(), s.row(), col.enabled);
				if (!Double.isNaN(on) && on <= 0) continue;
			}
			if (attack == null && col.type.equals("roll") && col.roll != null) attack = col;
			if (dice == null && col.type.equals("dice")) {
				String text = s.row().texts.getOrDefault(col.id, "").trim();
				if (!text.isEmpty()) {
					dice = col;
					diceFormula = col.modifier == null ? text : text + " + (" + col.modifier + ")";
				}
			}
		}
		boolean aimed = s.coll().targetable;
		boolean isDamage = damage || attack == null;
		SheetFormat.Col col = isDamage ? dice : attack;
		String formula = isDamage ? diceFormula : attack == null ? null : attack.roll;
		if (col == null || formula == null) {
			mc.player.sendMessage(Text.literal("[sheet] " + s.name() + " has nothing to roll.").formatted(Formatting.GRAY), false);
			return;
		}
		int kind = isDamage ? (aimed && s.coll().damage ? 2 : 0) : (aimed ? 1 : 0);
		// criticals: only a column that defines them takes part (Shift + right-click forces critical damage, Ctrl + right-click a normal one)
		String crit = isDamage ? dev.tacticalcombat.sheet.Crits.damage(col) : dev.tacticalcombat.sheet.Crits.attack(s.sc(), s.coll(), s.row(), col);
		int force = isDamage ? (net.minecraft.client.gui.screen.Screen.hasShiftDown() ? 1 : net.minecraft.client.gui.screen.Screen.hasControlDown() ? 2 : 0) : 0;
		String label = col.rollLabel.replace("{name}", s.name());
		if (!RollGate.tryStart(label)) return; // the same roll is still tumbling
		StringBuilder cost = new StringBuilder();
		if (!damage) {
			for (Map.Entry<String, Integer> e : s.cost().entrySet()) {
				if (cost.length() > 0) cost.append(',');
				cost.append(e.getKey()).append(':').append(e.getValue());
			}
		}
		try {
			Expr.Roll roll = s.sc().plan(s.coll(), s.row(), formula);
			ClientPlayNetworking.send(new DiceRequestPayload(label, roll.type(), roll.count(), roll.modifier(), 0, kind, cost.toString(), crit, force,
					dev.tacticalcombat.sheet.ConditionRules.rowRules(s.coll(), s.row()),
					dev.tacticalcombat.sheet.ConditionRules.ref(s.coll(), s.sc().character, s.row())));
		} catch (RuntimeException e) {
			RollGate.release(label);
			mc.player.sendMessage(Text.literal("[sheet] " + label + ": " + e.getMessage()).formatted(Formatting.RED), false);
			return;
		}
		FLASH.put(s.key(), System.currentTimeMillis());
		CharacterSheetScreen.autoShow(s.sc(), s.coll(), s.row());

		// a levelled spell uses up one of its slots (on your own sheet; a creature's sheet is not changed)
		if (!damage && s.level() >= 1 && s.own() && !s.store().isEmpty()) {
			String key = s.store() + s.level();
			CharacterData c = s.sc().character;
			c.values.put(key, Math.max(0, c.values.getOrDefault(key, 0.0) - 1));
			try {
				SheetLibrary.save(c);
			} catch (java.io.IOException e) {
				// the slot count stays as it was on disk; the next save fixes it
			}
		}
	}

	public static void reset() {
		dragging = false;
		openTray = -1;
		settingsOpen = false;
		trayScroll = 0;
		hotbar = List.of();
		lastTrays = List.of();
		HITS.clear();
		barRect = null;
		popRect = null;
		popRectGuess = null;
	}

	static ItemStack iconFor(CombatStatePayload.Entry e) {
		// ICONS uses String keys, so convert the Identifier and prefix it to avoid
		// colliding with ordinary action/tray icon IDs.
		String key = "entity:" + e.typeId().toString();
		return ICONS.computeIfAbsent(key, id -> {
			if (!e.hostile() || e.typeId().getNamespace().equals("tacticalcombat")) {
				return new ItemStack(Items.PLAYER_HEAD);
			}
			return new ItemStack(Items.PAPER);
		});
	}
}
