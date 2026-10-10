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

/** Initiative bar at the top; the turn panel (health, resources, movement, actions) is the {@link ActionBar}. */
public final class CombatHud {
	private static final int SLOT = 26;
	private static final int GAP = 3;

	private static final int COLOR_PLAYER = 0xFF3CB84A;
	private static final int COLOR_HOSTILE = 0xFFC83232;
	private static final int COLOR_ACTIVE = 0xFFFFD34D;
	private static final int COLOR_SLOT_BG = 0xFF1B1B1B;

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
		TargetArrows.render(ctx, mc, tickCounter.getTickDelta(false));
		ActionBar.render(ctx, mc, font, screenW, screenH);
	}

	private static void drawInitiativeBar(DrawContext ctx, TextRenderer font, int screenW, List<CombatStatePayload.Entry> entries) {
		int n = entries.size();
		int total = n * (SLOT + GAP) - GAP;
		int x0 = (screenW - total) / 2;
		int y0 = 18;

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

		// control hints, small and out of the way above the bar
		ctx.drawCenteredTextWithShadow(font, Text.translatable("tacticalcombat.hud.controls"),
				screenW / 2, 4, 0xFF9A9A9A);
	}

	static String resourceLabel(String id) {
		String key = "tacticalcombat.hud.res." + id;
		if (net.minecraft.client.resource.language.I18n.hasTranslation(key)) return Text.translatable(key).getString();
		if (id.equals("action")) return Text.translatable("tacticalcombat.hud.action").getString();
		if (id.equals("bonus")) return Text.translatable("tacticalcombat.hud.bonus").getString();
		return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	static Text nameOf(CombatStatePayload.Entry e) {
		if (!e.playerName().isEmpty()) return Text.literal(e.playerName());
		EntityType<?> type = Registries.ENTITY_TYPE.get(e.typeId());
		return type.getName();
	}

	static ItemStack iconFor(CombatStatePayload.Entry e) {
		return ICONS.computeIfAbsent(e.typeId(), id -> {
			if (!e.hostile() || id.getNamespace().equals("tacticalcombat")) return new ItemStack(Items.PLAYER_HEAD);
			EntityType<?> type = Registries.ENTITY_TYPE.get(id);
			SpawnEggItem egg = SpawnEggItem.forEntity(type);
			return egg != null ? new ItemStack(egg) : new ItemStack(Items.SKELETON_SKULL);
		});
	}
}
