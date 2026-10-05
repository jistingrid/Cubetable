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
	}

	private static void drawInitiativeBar(DrawContext ctx, TextRenderer font, int screenW, List<CombatStatePayload.Entry> entries) {
		int n = entries.size();
		int total = n * (SLOT + GAP) - GAP;
		int x0 = (screenW - total) / 2;
		int y0 = 6;

		for (int i = 0; i < n; i++) {
			CombatStatePayload.Entry e = entries.get(i);
			boolean active = i == ClientCombatState.activeIndex;
			int x = x0 + i * (SLOT + GAP);
			int y = active ? y0 + 5 : y0;

			int border = active ? COLOR_ACTIVE : (e.hostile() ? COLOR_HOSTILE : COLOR_PLAYER);
			int pad = active ? 2 : 1;
			ctx.fill(x - pad, y - pad, x + SLOT + pad, y + SLOT + pad, border);
			ctx.fill(x, y, x + SLOT, y + SLOT, COLOR_SLOT_BG);
			ctx.drawItem(iconFor(e), x + (SLOT - 16) / 2, y + (SLOT - 16) / 2);

			// initiative roll, small, in the corner
			ctx.drawTextWithShadow(font, String.valueOf(e.initiative()), x + 2, y + 2, 0xFFFFFFFF);

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
		ctx.fill(barX - 1, y - 1, barX + barW + 1, y + 7, 0xFF000000);
		ctx.fill(barX, y, barX + barW, y + 6, 0xFF2A2A2A);
		ctx.fill(barX, y, barX + Math.round(barW * (left / budget)), y + 6, COLOR_MOVE);
		String moveText = Text.translatable("tacticalcombat.hud.movement").getString()
				+ " " + String.format("%.0f / %.0f", left, budget);
		ctx.drawCenteredTextWithShadow(font, moveText, cx, y + 9, 0xFFFFFFFF);

		// action + bonus action pips
		int py = y + 22;
		drawPip(ctx, font, cx - 60, py, Text.translatable("tacticalcombat.hud.action").getString(),
				ClientCombatState.actionUsed ? COLOR_SPENT : COLOR_ACTION);
		drawPip(ctx, font, cx + 8, py, Text.translatable("tacticalcombat.hud.bonus").getString(),
				ClientCombatState.bonusUsed ? COLOR_SPENT : COLOR_BONUS);

		// end turn hint
		Text key = TacticalCombatClient.END_TURN_KEY.getBoundKeyLocalizedText();
		ctx.drawCenteredTextWithShadow(font, Text.translatable("tacticalcombat.hud.end_turn", key), cx, py + 14, 0xFFFFD34D);
	}

	private static void drawPip(DrawContext ctx, TextRenderer font, int x, int y, String label, int color) {
		ctx.fill(x, y, x + 9, y + 9, 0xFF000000);
		ctx.fill(x + 1, y + 1, x + 8, y + 8, color);
		ctx.drawTextWithShadow(font, label, x + 13, y + 1, 0xFFFFFFFF);
	}

	private static Text nameOf(CombatStatePayload.Entry e) {
		if (!e.playerName().isEmpty()) return Text.literal(e.playerName());
		EntityType<?> type = Registries.ENTITY_TYPE.get(e.typeId());
		return type.getName();
	}

	private static ItemStack iconFor(CombatStatePayload.Entry e) {
		return ICONS.computeIfAbsent(e.typeId(), id -> {
			if (!e.playerName().isEmpty()) return new ItemStack(Items.PLAYER_HEAD);
			EntityType<?> type = Registries.ENTITY_TYPE.get(id);
			SpawnEggItem egg = SpawnEggItem.forEntity(type);
			return egg != null ? new ItemStack(egg) : new ItemStack(Items.SKELETON_SKULL);
		});
	}
}
