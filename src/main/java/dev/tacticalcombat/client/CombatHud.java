package dev.tacticalcombat.client;

import dev.tacticalcombat.net.CombatStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Initiative bar at the top, turn / movement / action panel above the hotbar. */
public final class CombatHud {
	private static final int SLOT = 26;
	private static final int GAP = 3;

	private static final int COLOR_PLAYER = 0xFF3CB84A;
	private static final int COLOR_HOSTILE = 0xFFC83232;
	private static final int COLOR_ACTIVE = 0xFFFFD34D;
	private static final int COLOR_SLOT_BG = 0xFF1B1B1B;
	private static final int COLOR_ACTION = 0xFF4CD964;
	private static final int COLOR_BONUS = 0xFFFF9F2E;
	private static final int COLOR_MOVE = 0xFF4DA3FF;
	private static final int COLOR_SPENT = 0xFF555555;

	private static final Map<Identifier, ItemStack> ICONS = new HashMap<>();

	private CombatHud() {}

	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!ClientCombatState.active || mc.options.hudHidden || mc.player == null) return;

		List<CombatStatePayload.Entry> entries = ClientCombatState.entries;
		if (entries.isEmpty()) return;

		TextRenderer font = mc.textRenderer;
		int screenW = ctx.getScaledWindowWidth();
		int screenH = ctx.getScaledWindowHeight();

		drawInitiativeBar(ctx, font, screenW, entries);
		drawTurnPanel(ctx, mc, font, screenW, screenH, entries);
		drawOwnBars(ctx, mc, font, screenW, screenH, entries);
	}

	/** The bars the game tracks for your own character (hit points, sanity, stamina ...), bottom right. */
	private static void drawOwnBars(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW, int screenH,
									List<CombatStatePayload.Entry> entries) {
		CombatStatePayload.Entry mine = null;
		for (CombatStatePayload.Entry e : entries) if (e.entityId() == mc.player.getId()) mine = e;
		if (mine == null || mine.bars().isEmpty()) return;

		int labelW = 0;
		for (CombatStatePayload.Bar b : mine.bars()) labelW = Math.max(labelW, font.getWidth(b.label()));
		int barW = 62;
		int w = labelW + 6 + barW + 6 + font.getWidth("000 / 000");
		int x = Math.max(screenW / 2 + 100, screenW - w - 8);
		int rowH = 12;
		int y = screenH - 30 - mine.bars().size() * rowH;
		for (CombatStatePayload.Bar b : mine.bars()) {
			ctx.drawTextWithShadow(font, b.label(), x, y + 2, 0xFFDDDDDD);
			int bx = x + labelW + 6;
			float frac = b.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, b.now() / b.max()));
			ctx.fill(bx - 1, y + 1, bx + barW + 1, y + 10, 0xFF000000);
			ctx.fill(bx, y + 2, bx + barW, y + 9, 0xFF2A2A2A);
			ctx.fill(bx, y + 2, bx + Math.round(barW * frac), y + 9, b.color());
			ctx.drawTextWithShadow(font, Math.round(b.now()) + " / " + Math.round(b.max()), bx + barW + 6, y + 2, 0xFFFFFFFF);
			y += rowH;
		}
	}

	private static void drawInitiativeBar(DrawContext ctx, TextRenderer font, int screenW, List<CombatStatePayload.Entry> entries) {
		int n = entries.size();
		int total = n * (SLOT + GAP) - GAP;
		int x0 = (screenW - total) / 2;
		int y0 = 6;

		for (int i = 0; i < n; i++) {
			CombatStatePayload.Entry e = entries.get(i);
			boolean active = !ClientCombatState.planning && i == ClientCombatState.activeIndex;
			int x = x0 + i * (SLOT + GAP);
			int y = active ? y0 + 5 : y0;

			int border = active ? COLOR_ACTIVE : (e.hostile() ? COLOR_HOSTILE : COLOR_PLAYER);
			int pad = active ? 2 : 1;
			ctx.fill(x - pad, y - pad, x + SLOT + pad, y + SLOT + pad, border);
			ctx.fill(x, y, x + SLOT, y + SLOT, COLOR_SLOT_BG);
			ctx.drawItem(iconFor(e), x + (SLOT - 16) / 2, y + (SLOT - 16) / 2);

			// initiative roll, small, in the corner
			ctx.drawTextWithShadow(font, e.rolled() ? String.valueOf(e.initiative()) : "-", x + 2, y + 2, 0xFFFFFFFF);

			// health bar
			float frac = e.maxHealth() <= 0 ? 0 : Math.max(0f, Math.min(1f, e.health() / e.maxHealth()));
			int by = y + SLOT + 2;
			ctx.fill(x, by, x + SLOT, by + 3, 0xFF000000);
			int hpColor = e.hostile() ? 0xFFE04040 : 0xFF50D060;
			ctx.fill(x, by, x + Math.round(SLOT * frac), by + 3, hpColor);
		}

		// round counter under the bar, left aligned with it
		ctx.drawTextWithShadow(font, Text.translatable("tacticalcombat.hud.round", ClientCombatState.round),
				x0, y0 + SLOT + 12, 0xFFDDDDDD);

		// control hints, centred under the bar
		ctx.drawCenteredTextWithShadow(font, Text.translatable("tacticalcombat.hud.controls"),
				screenW / 2, y0 + SLOT + 26, 0xFF9A9A9A);
	}

	private static void drawTurnPanel(DrawContext ctx, MinecraftClient mc, TextRenderer font, int screenW, int screenH,
									  List<CombatStatePayload.Entry> entries) {
		int cx = screenW / 2;
		int y = screenH - 78;

		if (ClientCombatState.planning) {
			ctx.drawCenteredTextWithShadow(font, Text.translatable("tacticalcombat.hud.planning", TacticalCombatClient.ENCOUNTER_KEY.getBoundKeyLocalizedText()), cx, y, COLOR_ACTIVE);
			return;
		}
		boolean mine = ClientCombatState.isMyTurn();
		int idx = Math.min(ClientCombatState.activeIndex, entries.size() - 1);
		CombatStatePayload.Entry activeEntry = entries.get(Math.max(0, idx));

		Text header = mine
				? Text.translatable("tacticalcombat.hud.your_turn")
				: Text.translatable("tacticalcombat.hud.waiting", nameOf(activeEntry));
		ctx.drawCenteredTextWithShadow(font, header, cx, y - 14, mine ? COLOR_ACTIVE : 0xFFBBBBBB);

		if (!mine) return;

		// movement bar
		int barW = 120;
		int barX = cx - barW / 2;
		float budget = Math.max(0.001f, ClientCombatState.moveBudget);
		float left = Math.max(0f, budget - ClientCombatState.moveUsed);
		String unit = ClientCombatState.unit.isEmpty() ? "" : " " + ClientCombatState.unit;
		float sq = ClientCombatState.unit.isEmpty() ? 1f : ClientCombatState.square;
		ctx.fill(barX - 1, y - 1, barX + barW + 1, y + 7, 0xFF000000);
		ctx.fill(barX, y, barX + barW, y + 6, 0xFF2A2A2A);
		ctx.fill(barX, y, barX + Math.round(barW * (left / budget)), y + 6, COLOR_MOVE);
		String moveText = Text.translatable("tacticalcombat.hud.movement").getString()
				+ " " + String.format("%.0f / %.0f%s", left * sq, budget * sq, unit);
		ctx.drawCenteredTextWithShadow(font, moveText, cx, y + 9, 0xFFFFFFFF);

		// action + bonus action pips
		int py = y + 22;
		drawResources(ctx, font, cx, py);
		drawMoveButtons(ctx, mc, font);

		// end turn hint
		Text key = TacticalCombatClient.END_TURN_KEY.getBoundKeyLocalizedText();
		ctx.drawCenteredTextWithShadow(font, Text.translatable("tacticalcombat.hud.end_turn", key), cx, py + 14, 0xFFFFD34D);
	}

	/** Every per-turn resource of the pack (action, bonus, 3 actions ...) as a label and one pip per point. */
	private static void drawResources(DrawContext ctx, TextRenderer font, int cx, int y) {
		List<CombatStatePayload.Res> list = ClientCombatState.resources;
		if (list.isEmpty()) return;
		int total = 0;
		for (CombatStatePayload.Res r : list) total += font.getWidth(resourceLabel(r.id())) + 4 + Math.min(r.max(), 8) * 11 + 10;
		int x = cx - (total - 10) / 2;
		int index = 0;
		for (CombatStatePayload.Res r : list) {
			String label = resourceLabel(r.id());
			ctx.drawTextWithShadow(font, label, x, y + 1, 0xFFFFFFFF);
			x += font.getWidth(label) + 4;
			int color = switch (r.id()) {
				case "action" -> COLOR_ACTION;
				case "bonus" -> COLOR_BONUS;
				default -> 0xFFB48CFF;
			};
			int pips = Math.min(r.max(), 8);
			for (int i = 0; i < pips; i++) {
				ctx.fill(x, y, x + 9, y + 9, 0xFF000000);
				ctx.fill(x + 1, y + 1, x + 8, y + 8, i < r.left() ? color : COLOR_SPENT);
				x += 11;
			}
			x += 10;
			index++;
		}
	}

	private static String resourceLabel(String id) {
		String key = "tacticalcombat.hud.res." + id;
		if (net.minecraft.client.resource.language.I18n.hasTranslation(key)) return Text.translatable(key).getString();
		if (id.equals("action")) return Text.translatable("tacticalcombat.hud.action").getString();
		if (id.equals("bonus")) return Text.translatable("tacticalcombat.hud.bonus").getString();
		return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	/** Placement of the buttons for movement options the player buys (Dash ...), right of the movement bar. */
	public static int[] moveButtonRect(MinecraftClient mc, int index) {
		CombatStatePayload.MoveButton b = ClientCombatState.moveButtons.get(index);
		int w = mc.textRenderer.getWidth(buttonText(b)) + 8;
		int x = mc.getWindow().getScaledWidth() / 2 + 66;
		int y = mc.getWindow().getScaledHeight() - 78 - 2 + index * 14;
		return new int[] {x, y, w, 12};
	}

	private static String buttonText(CombatStatePayload.MoveButton b) {
		return b.cost().isEmpty() ? b.label() : b.label() + " (" + b.cost() + ")";
	}

	private static void drawMoveButtons(DrawContext ctx, MinecraftClient mc, TextRenderer font) {
		double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
		double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
		for (int i = 0; i < ClientCombatState.moveButtons.size(); i++) {
			CombatStatePayload.MoveButton b = ClientCombatState.moveButtons.get(i);
			int[] r = moveButtonRect(mc, i);
			boolean over = b.enabled() && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
			ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF000000);
			ctx.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, !b.enabled() ? 0xFF2A2A2A : over ? 0xFF3A6EA8 : 0xFF244A75);
			ctx.drawTextWithShadow(font, buttonText(b), r[0] + 4, r[1] + 2, b.enabled() ? 0xFFFFFFFF : 0xFF888888);
		}
	}

	private static void drawPip(DrawContext ctx, TextRenderer font, int x, int y, String label, int color) {
		ctx.fill(x, y, x + 9, y + 9, 0xFF000000);
		ctx.fill(x + 1, y + 1, x + 8, y + 8, color);
		ctx.drawTextWithShadow(font, label, x + 13, y + 1, 0xFFFFFFFF);
	}

	static Text nameOf(CombatStatePayload.Entry e) {
		if (!e.playerName().isEmpty()) return Text.literal(e.playerName());
		EntityType<?> type = Registries.ENTITY_TYPE.get(e.typeId());
		return type.getName();
	}

	static ItemStack iconFor(CombatStatePayload.Entry e) {
		return ICONS.computeIfAbsent(e.typeId(), id -> {
			if (!e.playerName().isEmpty()) return new ItemStack(Items.PLAYER_HEAD);
			EntityType<?> type = Registries.ENTITY_TYPE.get(id);
			SpawnEggItem egg = SpawnEggItem.forEntity(type);
			return egg != null ? new ItemStack(egg) : new ItemStack(Items.SKELETON_SKULL);
		});
	}
}
