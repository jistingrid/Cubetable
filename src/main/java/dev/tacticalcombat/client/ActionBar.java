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
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bar at the bottom centre of the combat view: your health bars, the resources of the turn, movement (with the
 * moves you can buy), one slot for every weapon / spell / feature the pack lists in its "actions" block, and End Turn.
 *
 * <p>Everything but the bar's frame comes from the pack and the sheet, so a game without actions or without health
 * bars simply gets a smaller bar. Drawn from the HUD callback; clicks and number keys are routed in by
 * {@link TacticalScreen}.
 */
public final class ActionBar {
	private static final int PAD = 4;
	private static final int SLOT = 22;
	private static final int GAP = 2;
	private static final int ROW = 11;
	private static final int SEP = 7;
	private static final int MAX_SLOTS = 9;
	/** Distance from the bottom of the screen in the default place (Minecraft's own hotbar is hidden in a fight). */
	private static final int MARGIN = 6;

	private static final int PANEL = 0xE61D2029;
	private static final int EDGE = 0xFF3A3F4D;
	private static final int ACCENT = 0xFFE0B84C;
	private static final int SLOT_BG = 0xFF252936;
	private static final int TEXT = 0xFFE8E6DF;
	private static final int MUTED = 0xFF9AA0AE;
	private static final int END_BG = 0xFF8E2A35;
	private static final int END_HOVER = 0xFFA3303D;
	private static final int ACTION = 0xFFE0B84C;
	private static final int BONUS = 0xFF5FB7A8;
	private static final int OTHER = 0xFFB48CFF;
	private static final int SPENT = 0xFF444A58;
	private static final int MOVE = 0xFFE0B84C;

	/** Kept for the slot highlight; clicking a slot now rolls it instead of selecting it. */
	public static int selected = -1;

	/** Where the player dragged the bar: its centre and bottom edge (so it keeps its anchor as it changes width). -1 = default. */
	private static int customCx = -1;
	private static int customBottom = -1;
	private static boolean dragging;

	private static final Map<String, ItemStack> ICONS = new HashMap<>();

	private ActionBar() {}

	/** One slot, already evaluated on the character's sheet; the references let a click roll it. */
	public record Slot(String name, ItemStack icon, int level, Map<String, Integer> cost, String costText,
					   int left, int max, boolean usable,
					   SheetContext sc, SheetFormat.Collection coll, CharacterData.Row row, boolean own, String store) {}

	/** Where everything sits; computed once per frame and shared by drawing and clicking. */
	private static final class Layout {
		int x, y, w, h;
		int[] hp, res, move, text, slots, end;
		final List<int[]> slotRects = new ArrayList<>();
		final List<int[]> moveRects = new ArrayList<>();
		int hidden;
	}

	private static List<Slot> lastSlots = List.of();
	private static int lastVisible;

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

	private static List<Slot> buildSlots(CombatStatePayload.Entry mine, boolean myTurn) {
		if (mine == null) return List.of();
		MinecraftClient mcl = MinecraftClient.getInstance();
		boolean own = mcl.player != null && mine.entityId() == mcl.player.getId();
		CharacterData c = own ? ServerCharacters.fighter(mine.sheetId()) : ServerCharacters.byId(mine.sheetId());
		if (c == null) return List.of();
		SheetFormat f = SheetLibrary.FORMATS.get(c.format);
		if (f == null || f.combat == null || f.combat.sources.isEmpty()) return List.of();
		CombatRules rules = f.combat;
		SheetContext sc = new SheetContext(f, c);
		String main = rules.resources.isEmpty() ? "" : rules.resources.keySet().iterator().next();

		List<Slot> out = new ArrayList<>();
		for (CombatRules.Source s : rules.sources) {
			SheetFormat.Collection coll = f.collections.get(s.collection());
			if (coll == null) continue;
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
				if (usable) {
					for (Map.Entry<String, Integer> e : cost.entrySet()) {
						if (resourceLeft(e.getKey()) < e.getValue()) usable = false;
					}
				}
				String mode = s.show().isEmpty() ? rules.showCost : s.show();
				out.add(new Slot(name, iconFor(s.icon()), level, cost, costText(cost, main, mode), left, max, usable,
						sc, coll, row, own && !c.remote, s.slotStore()));
			}
		}
		return out;
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

	// ------------------------------------------------------------------ layout

	private static Layout layout(MinecraftClient mc, int screenW, int screenH, CombatStatePayload.Entry mine,
								 boolean myTurn, List<Slot> slots) {
		TextRenderer font = mc.textRenderer;
		Layout L = new Layout();
		List<Integer> widths = new ArrayList<>();
		List<Integer> heights = new ArrayList<>();

		int hpW = 0;
		int hpH = 0;
		if (mine != null && !barsOf(mine).isEmpty()) {
			int labelW = 0;
			int numW = font.getWidth("000 / 000");
			for (CombatStatePayload.Bar b : barsOf(mine)) labelW = Math.max(labelW, font.getWidth(b.label()));
			hpW = labelW + 4 + 44 + 4 + numW;
			hpH = barsOf(mine).size() * ROW;
			widths.add(hpW);
			heights.add(hpH);
		}

		boolean planning = ClientCombatState.planning;
		int textW = 0;
		int resW = 0;
		int resH = 0;
		int moveW = 0;
		int moveH = 0;
		List<Integer> buttonW = new ArrayList<>();
		if (myTurn) {
			for (CombatStatePayload.Res r : ClientCombatState.resources) {
				resW = Math.max(resW, font.getWidth(CombatHud.resourceLabel(r.id())) + 4 + Math.min(r.max(), 8) * 9);
			}
			resH = ClientCombatState.resources.size() * ROW;
			if (resW > 0) {
				widths.add(resW);
				heights.add(resH);
			}
			int btnTotal = 0;
			for (CombatStatePayload.MoveButton b : ClientCombatState.moveButtons) {
				int bw = font.getWidth(buttonText(b)) + 8;
				buttonW.add(bw);
				btnTotal += bw + 2;
			}
			moveW = Math.max(104, btnTotal - 2);
			moveH = ROW + 8 + (buttonW.isEmpty() ? 0 : 14);
			widths.add(moveW);
			heights.add(moveH);
		} else {
			Text t = planning
					? Text.translatable("tacticalcombat.hud.planning", TacticalCombatClient.ENCOUNTER_KEY.getBoundKeyLocalizedText())
					: waitingText();
			textW = font.getWidth(t) + 4;
			widths.add(textW);
			heights.add(ROW);
		}

		int endW = 0;
		if (myTurn) {
			endW = Math.max(font.getWidth(Text.translatable("tacticalcombat.hud.end_turn_short")), 40) + 12;
			widths.add(endW);
			heights.add(SLOT);
		}

		// slots take whatever room is left, at most nine (the number keys)
		int fixed = PAD * 2;
		for (int w : widths) fixed += w;
		int groups = widths.size() + (slots.isEmpty() ? 0 : 1);
		fixed += SEP * Math.max(0, groups - 1);
		int room = screenW - 12 - fixed;
		int fit = Math.max(0, Math.min(MAX_SLOTS, (room + GAP) / (SLOT + GAP)));
		int visible = Math.min(slots.size(), fit);
		L.hidden = slots.size() - visible;
		int slotsW = visible == 0 ? 0 : visible * (SLOT + GAP) - GAP;

		int total = PAD * 2 + SEP * Math.max(0, (widths.size() + (visible > 0 ? 1 : 0)) - 1);
		for (int w : widths) total += w;
		total += slotsW;
		int maxH = visible > 0 ? SLOT : 0;
		for (int h : heights) maxH = Math.max(maxH, h);
		L.w = Math.max(total, 40);
		L.h = PAD * 2 + Math.max(maxH, ROW);
		int cxBar = customCx >= 0 ? customCx : screenW / 2;
		int bottom = customBottom >= 0 ? customBottom : screenH - MARGIN;
		L.x = Math.max(2, Math.min(screenW - L.w - 2, cxBar - L.w / 2));
		L.y = Math.max(2, Math.min(screenH - L.h - 2, bottom - L.h));

		// place the groups left to right, each one centred vertically
		int cx = L.x + PAD;
		int mid = L.y + L.h / 2;
		if (hpW > 0) {
			L.hp = new int[] {cx, mid - hpH / 2, hpW, hpH};
			cx += hpW + SEP;
		}
		if (myTurn) {
			if (resW > 0) {
				L.res = new int[] {cx, mid - resH / 2, resW, resH};
				cx += resW + SEP;
			}
			L.move = new int[] {cx, mid - moveH / 2, moveW, moveH};
			int bx = cx;
			int by = L.move[1] + ROW + 8;
			for (int bw : buttonW) {
				L.moveRects.add(new int[] {bx, by, bw, 12});
				bx += bw + 2;
			}
			cx += moveW + SEP;
		} else {
			L.text = new int[] {cx, mid - ROW / 2, textW, ROW};
			cx += textW + SEP;
		}
		if (visible > 0) {
			L.slots = new int[] {cx, mid - SLOT / 2, slotsW, SLOT};
			for (int i = 0; i < visible; i++) L.slotRects.add(new int[] {cx + i * (SLOT + GAP), mid - SLOT / 2, SLOT, SLOT});
			cx += slotsW + SEP;
		}
		if (myTurn) {
			L.end = new int[] {cx, mid - SLOT / 2, endW, SLOT};
		}
		lastVisible = visible;
		return L;
	}

	private static Text waitingText() {
		int idx = ClientCombatState.activeIndex;
		if (idx < 0 || idx >= ClientCombatState.entries.size()) return Text.empty();
		return Text.translatable("tacticalcombat.hud.waiting", CombatHud.nameOf(ClientCombatState.entries.get(idx)));
	}

	private static String buttonText(CombatStatePayload.MoveButton b) {
		return b.cost().isEmpty() ? b.label() : b.label() + " (" + b.cost() + ")";
	}

	private static Layout current(MinecraftClient mc, int screenW, int screenH) {
		boolean myTurn = ClientCombatState.canMoveActive();
		CombatStatePayload.Entry mine = mine(mc);
		List<Slot> slots = buildSlots(mine, myTurn);
		lastSlots = slots;
		return layout(mc, screenW, screenH, mine, myTurn, slots);
	}

	// ------------------------------------------------------------------ drawing

	public static void render(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW, int screenH) {
		boolean myTurn = ClientCombatState.canMoveActive();
		CombatStatePayload.Entry mine = mine(mc);
		List<Slot> slots = buildSlots(mine, myTurn);
		lastSlots = slots;
		Layout L = layout(mc, screenW, screenH, mine, myTurn, slots);
		if (!myTurn) selected = -1;
		if (selected >= lastVisible) selected = -1;

		// nothing to show at all (no sheet bars, no slots, nobody's turn text): skip the frame entirely
		if (L.hp == null && L.slots == null && L.text != null && L.text[2] <= 4) return;

		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();

		ctx.fill(L.x - 1, L.y - 1, L.x + L.w + 1, L.y + L.h + 1, myTurn ? ACCENT : EDGE);
		ctx.fill(L.x, L.y, L.x + L.w, L.y + L.h, PANEL);

		int sepTop = L.y + 4;
		int sepBottom = L.y + L.h - 4;

		if (L.hp != null) {
			drawBars(ctx, font, L.hp, mine);
			drawSep(ctx, L, L.hp, sepTop, sepBottom);
		}
		if (myTurn) {
			if (L.res != null) {
				drawResources(ctx, font, L.res);
				drawSep(ctx, L, L.res, sepTop, sepBottom);
			}
			drawMove(ctx, font, L, mx, my);
			drawSep(ctx, L, L.move, sepTop, sepBottom);
		} else if (L.text != null) {
			Text t = ClientCombatState.planning
					? Text.translatable("tacticalcombat.hud.planning", TacticalCombatClient.ENCOUNTER_KEY.getBoundKeyLocalizedText())
					: waitingText();
			ctx.drawTextWithShadow(font, t, L.text[0] + 2, L.text[1] + 1, MUTED);
			if (L.slots != null) drawSep(ctx, L, L.text, sepTop, sepBottom);
		}

		List<Text> tip = null;
		for (int i = 0; i < L.slotRects.size(); i++) {
			int[] r = L.slotRects.get(i);
			Slot s = slots.get(i);
			boolean over = inside(mx, my, r);
			drawSlot(ctx, font, r, i, s, i == selected, over);
			if (over) tip = slotTip(s);
		}
		if (L.slots != null && L.end != null) drawSep(ctx, L, L.slots, sepTop, sepBottom);
		if (L.hidden > 0 && L.slots != null) {
			String more = "+" + L.hidden;
			ctx.drawTextWithShadow(font, more, L.slots[0] + L.slots[2] - font.getWidth(more), L.y - 10, MUTED);
		}

		if (L.end != null) {
			boolean over = inside(mx, my, L.end);
			ctx.fill(L.end[0], L.end[1], L.end[0] + L.end[2], L.end[1] + L.end[3], over ? END_HOVER : END_BG);
			Text label = Text.translatable("tacticalcombat.hud.end_turn_short");
			ctx.drawCenteredTextWithShadow(font, label, L.end[0] + L.end[2] / 2, L.end[1] + 3, 0xFFFFFFFF);
			ctx.drawCenteredTextWithShadow(font, TacticalCombatClient.END_TURN_KEY.getBoundKeyLocalizedText(),
					L.end[0] + L.end[2] / 2, L.end[1] + 12, 0xFFE9B4BA);
		}

		if (tip == null && !dragging && inside(mx, my, new int[] {L.x, L.y, L.w, L.h}) && !overControl(L, mx, my)) {
			tip = List.of(Text.translatable("tacticalcombat.bar.drag"));
		}
		if (tip != null) ctx.drawTooltip(font, tip, (int) mx, (int) my);
	}

	/** The pointer is on a slot, a move button or End Turn (as opposed to the bar's background, which drags it). */
	private static boolean overControl(Layout L, double mx, double my) {
		for (int[] r : L.slotRects) if (inside(mx, my, r)) return true;
		for (int[] r : L.moveRects) if (inside(mx, my, r)) return true;
		return L.end != null && inside(mx, my, L.end);
	}

	private static void drawSep(DrawContext ctx, Layout L, int[] group, int top, int bottom) {
		int x = group[0] + group[2] + SEP / 2;
		ctx.fill(x, top, x + 1, bottom, EDGE);
	}

	private static void drawBars(DrawContext ctx, TextRenderer font, int[] g, CombatStatePayload.Entry mine) {
		int labelW = 0;
		for (CombatStatePayload.Bar b : barsOf(mine)) labelW = Math.max(labelW, font.getWidth(b.label()));
		int barW = 44;
		int y = g[1];
		for (CombatStatePayload.Bar b : barsOf(mine)) {
			ctx.drawTextWithShadow(font, b.label(), g[0], y + 2, TEXT);
			int bx = g[0] + labelW + 4;
			float frac = b.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, b.now() / b.max()));
			ctx.fill(bx - 1, y + 1, bx + barW + 1, y + 10, 0xFF000000);
			ctx.fill(bx, y + 2, bx + barW, y + 9, 0xFF2A2A2A);
			ctx.fill(bx, y + 2, bx + Math.round(barW * frac), y + 9, b.color());
			ctx.drawTextWithShadow(font, Math.round(b.now()) + " / " + Math.round(b.max()), bx + barW + 4, y + 2, 0xFFFFFFFF);
			y += ROW;
		}
	}

	private static void drawResources(DrawContext ctx, TextRenderer font, int[] g) {
		int y = g[1];
		for (CombatStatePayload.Res r : ClientCombatState.resources) {
			String label = CombatHud.resourceLabel(r.id());
			ctx.drawTextWithShadow(font, label, g[0], y + 2, MUTED);
			int x = g[0] + font.getWidth(label) + 4;
			boolean diamond = r.id().equals("bonus");
			int color = switch (r.id()) {
				case "action" -> ACTION;
				case "bonus" -> BONUS;
				default -> OTHER;
			};
			int pips = Math.min(r.max(), 8);
			for (int i = 0; i < pips; i++) {
				drawShape(ctx, x, y + 2, diamond, i < r.left() ? color : SPENT);
				x += 9;
			}
			y += ROW;
		}
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

	private static void drawMove(DrawContext ctx, TextRenderer font, Layout L, double mx, double my) {
		int[] g = L.move;
		float budget = Math.max(0.001f, ClientCombatState.moveBudget);
		float left = Math.max(0f, budget - ClientCombatState.moveUsed);
		float sq = ClientCombatState.unit.isEmpty() ? 1f : ClientCombatState.square;
		String unit = ClientCombatState.unit.isEmpty() ? "" : " " + ClientCombatState.unit;
		ctx.drawTextWithShadow(font, Text.translatable("tacticalcombat.hud.movement"), g[0], g[1] + 1, MUTED);
		String nums = String.format("%.0f / %.0f%s", left * sq, budget * sq, unit);
		ctx.drawTextWithShadow(font, nums, g[0] + g[2] - font.getWidth(nums), g[1] + 1, TEXT);

		int by = g[1] + ROW + 1;
		ctx.fill(g[0] - 1, by - 1, g[0] + g[2] + 1, by + 7, 0xFF000000);
		ctx.fill(g[0], by, g[0] + g[2], by + 6, 0xFF0F1117);
		ctx.fill(g[0], by, g[0] + Math.round(g[2] * (left / budget)), by + 6, MOVE);
		if (budget <= 12 && g[2] / budget >= 4) { // one tick per grid square
			for (int i = 1; i < Math.round(budget); i++) {
				int tx = g[0] + Math.round(g[2] * (i / budget));
				ctx.fill(tx, by, tx + 1, by + 6, 0xFF0F1117);
			}
		}

		for (int i = 0; i < L.moveRects.size(); i++) {
			CombatStatePayload.MoveButton b = ClientCombatState.moveButtons.get(i);
			int[] r = L.moveRects.get(i);
			boolean over = b.enabled() && inside(mx, my, r);
			ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], EDGE);
			ctx.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, !b.enabled() ? 0xFF1A1D26 : over ? 0xFF343A4C : SLOT_BG);
			ctx.drawTextWithShadow(font, buttonText(b), r[0] + 4, r[1] + 2, b.enabled() ? TEXT : 0xFF6A7080);
		}
	}

	private static void drawSlot(DrawContext ctx, TextRenderer font, int[] r, int index, Slot s, boolean sel, boolean over) {
		int border = sel ? ACCENT : over && s.usable() ? 0xFF8A8F9E : EDGE;
		ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], border);
		ctx.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, SLOT_BG);
		ctx.drawItem(s.icon(), r[0] + (SLOT - 16) / 2, r[1] + (SLOT - 16) / 2);
		if (!s.usable()) ctx.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, 0xA0101218);

		// small corner texts at half size: key, level, cost
		var m = ctx.getMatrices();
		m.push();
		m.scale(0.5f, 0.5f, 1f);
		ctx.drawText(font, String.valueOf(index + 1), (r[0] + 3) * 2, (r[1] + 2) * 2, MUTED, false);
		if (s.level() >= 1) {
			String lv = String.valueOf(s.level());
			ctx.drawText(font, lv, (r[0] + r[2] - 3) * 2 - font.getWidth(lv), (r[1] + 2) * 2, 0xFF9FA9F0, false);
		}
		if (!s.costText().isEmpty()) {
			ctx.drawText(font, s.costText(), (r[0] + r[2] - 3) * 2 - font.getWidth(s.costText()), (r[1] + r[3] - 7) * 2, ACCENT, false);
		}
		m.pop();

		// spell slots left, one dot each, along the bottom edge
		if (s.max() > 0) {
			int dots = Math.min(s.max(), 6);
			for (int i = 0; i < dots; i++) {
				int dx = r[0] + 3 + i * 3;
				int dy = r[1] + r[3] - 4;
				ctx.fill(dx, dy, dx + 2, dy + 2, i < s.left() ? 0xFF7D8BD6 : 0xFF3A3F55);
			}
		}
	}

	private static List<Text> slotTip(Slot s) {
		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal(s.name()));
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
		lines.add(Text.translatable("tacticalcombat.bar.use").formatted(net.minecraft.util.Formatting.GRAY));
		if (!s.usable()) lines.add(Text.translatable("tacticalcombat.bar.unavailable"));
		return lines;
	}

	private static boolean inside(double mx, double my, int[] r) {
		return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	// ------------------------------------------------------------------ input

	/** True when the pointer is over the bar (the battlefield underneath must not react). */
	public static boolean contains(double mx, double my, int screenW, int screenH) {
		if (!ClientCombatState.active) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		Layout L = current(mc, screenW, screenH);
		return mx >= L.x - 1 && mx < L.x + L.w + 1 && my >= L.y - 1 && my < L.y + L.h + 1;
	}

	/**
	 * Handles a click on the bar; true when it landed on it. Left click on a control uses it; left click on the
	 * background starts dragging the bar; right click on the background puts the bar back in its default place.
	 */
	public static boolean mouseClicked(double mx, double my, int button, int screenW, int screenH) {
		if (!ClientCombatState.active) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		Layout L = current(mc, screenW, screenH);
		if (!(mx >= L.x - 1 && mx < L.x + L.w + 1 && my >= L.y - 1 && my < L.y + L.h + 1)) return false;
		boolean control = overControl(L, mx, my);

		if (button == 1 && !control) {
			customCx = -1;
			customBottom = -1;
			return true;
		}
		if (button == 1 && control && ClientCombatState.canMoveActive()) { // right click: the damage of that slot
			for (int i = 0; i < L.slotRects.size(); i++) {
				if (inside(mx, my, L.slotRects.get(i))) use(i, true);
			}
			return true;
		}
		if (button != 0) return true;

		if (ClientCombatState.canMoveActive()) {
			for (int i = 0; i < L.moveRects.size(); i++) {
				if (inside(mx, my, L.moveRects.get(i))) {
					CombatStatePayload.MoveButton b = ClientCombatState.moveButtons.get(i);
					if (b.enabled()) ClientPlayNetworking.send(new dev.tacticalcombat.net.BuyMovePayload(b.id()));
					return true;
				}
			}
			for (int i = 0; i < L.slotRects.size(); i++) {
				if (inside(mx, my, L.slotRects.get(i))) {
					use(i, false);
					return true;
				}
			}
			if (L.end != null && inside(mx, my, L.end)) {
				if (ClientCombatState.isMyTurn()) TacticalCombatClient.sendEndTurn();
				else ClientPlayNetworking.send(new EncounterActionPayload(6, 0, 0)); // the Dungeon Master ends the creature's turn
				return true;
			}
		}
		if (!control) dragging = true;
		return true;
	}

	/** Moves the bar while the background is dragged; true when a drag is in progress. */
	public static boolean mouseDragged(double dx, double dy, int screenW, int screenH) {
		if (!dragging) return false;
		Layout L = current(MinecraftClient.getInstance(), screenW, screenH);
		customCx = (int) Math.round(L.x + L.w / 2.0 + dx);
		customBottom = (int) Math.round(L.y + L.h + dy);
		return true;
	}

	public static boolean mouseReleased() {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	/** Number keys 1 to 9 pick the matching slot. True when the key was used. */
	public static boolean keyPressed(int keyCode) {
		if (!ClientCombatState.canMoveActive()) return false;
		if (keyCode < GLFW.GLFW_KEY_1 || keyCode > GLFW.GLFW_KEY_9) return false;
		int index = keyCode - GLFW.GLFW_KEY_1;
		if (index >= lastVisible || index >= lastSlots.size()) return false;
		use(index, false);
		return true;
	}

	/**
	 * Uses a slot. Left click: its attack roll (aimed at your target; the cost is spent and a spell slot used), or its
	 * dice when it has no attack. Right click: its damage dice, which cost nothing.
	 */
	private static void use(int index, boolean damage) {
		if (index < 0 || index >= lastSlots.size()) return;
		Slot s = lastSlots.get(index);
		// a greyed slot (its cost is paid, its spell slots gone) can not attack any more, but its damage is free: the
		// attack roll that just spent the action must still be followed by a damage roll
		if (!s.usable() && !damage) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		if (!ClientPlayNetworking.canSend(DiceRequestPayload.ID)) return;

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
			mc.player.sendMessage(Text.literal("[sheet] " + s.name() + " has nothing to roll.").formatted(net.minecraft.util.Formatting.GRAY), false);
			return;
		}
		int kind = isDamage ? (aimed && s.coll().damage ? 2 : 0) : (aimed ? 1 : 0);
		String label = col.rollLabel.replace("{name}", s.name());
		StringBuilder cost = new StringBuilder();
		if (!damage) {
			for (Map.Entry<String, Integer> e : s.cost().entrySet()) {
				if (cost.length() > 0) cost.append(',');
				cost.append(e.getKey()).append(':').append(e.getValue());
			}
		}
		try {
			Expr.Roll roll = s.sc().plan(s.coll(), s.row(), formula);
			ClientPlayNetworking.send(new DiceRequestPayload(label, roll.type(), roll.count(), roll.modifier(), 0, kind, cost.toString()));
		} catch (RuntimeException e) {
			mc.player.sendMessage(Text.literal("[sheet] " + label + ": " + e.getMessage()).formatted(net.minecraft.util.Formatting.RED), false);
			return;
		}

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
		selected = -1;
		dragging = false;
		lastSlots = List.of();
		lastVisible = 0;
	}
}
