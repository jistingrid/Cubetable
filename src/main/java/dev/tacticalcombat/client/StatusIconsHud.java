package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DmStatePayload;
import dev.tacticalcombat.net.CombatStatePayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import dev.tacticalcombat.sheet.StatusIcons;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * The conditions of the character you control, as icons in the top-right corner of the screen (below Minecraft's own
 * status effects): your character, or the Actor a Dungeon Master possesses. Which icon a condition has comes from its
 * sheet row (a column of type {@code icon}), else from the game pack's preset of the same name; a condition without
 * either shows a lettered badge. A number on the icon is the time it has left.
 */
final class StatusIconsHud {
	private static final int SIZE = 20;
	private static final int GAP = 3;
	private static final int PER_ROW = 8;

	private StatusIconsHud() {}

	static void render(DrawContext ctx, RenderTickCounter tick) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		List<Badge> badges = badges(mc);
		if (badges.isEmpty()) return;
		TextRenderer font = mc.textRenderer;
		int right = ctx.getScaledWindowWidth() - 6;
		int top = mc.player.getStatusEffects().isEmpty() ? 6 : 58; // vanilla effects take two rows of 25
		for (int i = 0; i < badges.size(); i++) {
			Badge b = badges.get(i);
			int col = i % PER_ROW;
			int row = i / PER_ROW;
			int x = right - SIZE - col * (SIZE + GAP);
			int y = top + row * (SIZE + GAP);
			StatusIconDraw.badge(ctx, font, b.icon, b.name, x, y, SIZE);
			if (!b.time.isEmpty()) {
				ctx.getMatrices().push();
				ctx.getMatrices().translate(0, 0, 200);
				ctx.drawText(font, b.time, x + SIZE - font.getWidth(b.time), y + SIZE - 8, 0xFFFFFFFF, true);
				ctx.getMatrices().pop();
			}
		}
	}

	private record Badge(String name, String icon, String time) {}

	/** The controlled character's sheet: a possessed Actor's, else the fighter the server uses for this player. */
	private static CharacterData controlled(MinecraftClient mc) {
		if (DmState.possessed >= 0) {
			for (DmStatePayload.ActorInfo a : DmState.actors) {
				if (a.entityId() == DmState.possessed) return ServerCharacters.byId(a.sheetId());
			}
			return null;
		}
		String id = "";
		for (CombatStatePayload.Entry e : ClientCombatState.entries) {
			if (e.entityId() == mc.player.getId()) id = e.sheetId();
		}
		return ServerCharacters.fighter(id);
	}

	private static List<Badge> badges(MinecraftClient mc) {
		List<Badge> out = new ArrayList<>();
		CharacterData c = controlled(mc);
		SheetFormat f = c == null ? null : SheetLibrary.FORMATS.get(c.format);
		if (f == null || f.combat == null || f.combat.conditions == null) return out;
		var rule = f.combat.conditions;
		SheetFormat.Collection coll = f.collections.get(rule.collection());
		if (coll == null) return out;
		SheetFormat.Col nameCol = coll.nameCol();
		SheetFormat.Col durCol = coll.column(rule.duration());
		SheetFormat.Col unitCol = coll.column(rule.unit());
		for (CharacterData.Row row : c.collections.getOrDefault(coll.id, List.of())) {
			String name = nameCol == null ? "" : row.texts.getOrDefault(nameCol.id, "").trim();
			if (name.isEmpty()) continue;
			String time = "";
			if (durCol != null) {
				double left = row.values.getOrDefault(durCol.id, durCol.def);
				if (left > 0) {
					time = String.valueOf((long) Math.ceil(left));
					if (unitCol != null && !unitCol.options.isEmpty()) {
						int u = Math.floorMod((int) Math.round(row.values.getOrDefault(unitCol.id, unitCol.def)), unitCol.options.size());
						if (unitCol.options.get(u).equalsIgnoreCase(rule.seconds())) time += "s";
					}
				}
			}
			out.add(new Badge(name, StatusIcons.iconOf(coll, row), time));
		}
		return out;
	}
}
