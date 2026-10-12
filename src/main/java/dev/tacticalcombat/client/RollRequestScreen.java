package dev.tacticalcombat.client;

import dev.tacticalcombat.net.DmStatePayload;
import dev.tacticalcombat.net.RollRequestPayload;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Dungeon Master's Roll request tool: pick who (players and placed Actors), what kind of roll (the checks of the
 * game packs, or a plain d20) and the DC, and each one's owner gets the roll prompt. Session choices are kept for the
 * next time the window opens.
 */
public final class RollRequestScreen extends Screen {
	private record Who(int entityId, String name, String tag) {}

	private static final Set<Integer> PICKED = new LinkedHashSet<>();
	private static int checkIndex;
	private static String dcText = "";
	private static boolean showDc = true;

	private static final int W = 260;
	private static final int ROW = 13;
	private static final int ROWS = 9;

	private final List<Who> targets = new ArrayList<>();
	private final List<String[]> checks = new ArrayList<>(); // id, label
	private TextFieldWidget dcBox;
	private ButtonWidget checkButton;
	private ButtonWidget showButton;
	private int scroll;
	private int left;
	private int top;
	private int listTop;
	private String note = "";

	public RollRequestScreen() {
		super(Text.literal("Roll request"));
	}

	@Override
	protected void init() {
		targets.clear();
		for (DmStatePayload.Who p : DmState.players) targets.add(new Who(p.entityId(), p.name(), "player"));
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.entityId() >= 0) targets.add(new Who(a.entityId(), a.name(), "actor"));
		PICKED.removeIf(id -> targets.stream().noneMatch(t -> t.entityId() == id));

		// every check any game pack offers, once each
		Map<String, String> all = new LinkedHashMap<>();
		all.put("", "Plain d20");
		for (SheetFormat f : SheetLibrary.FORMATS.values()) {
			if (f.combat == null) continue;
			for (CombatRules.Check c : f.combat.checks) all.putIfAbsent(c.id(), c.label());
		}
		checks.clear();
		for (Map.Entry<String, String> e : all.entrySet()) checks.add(new String[] {e.getKey(), e.getValue()});
		checkIndex = Math.floorMod(checkIndex, checks.size());

		left = (width - W) / 2;
		top = Math.max(8, (height - (60 + ROWS * ROW + 70)) / 2);
		int y = top + 22;
		addDrawableChild(ButtonWidget.builder(Text.literal("All players"), b -> pick(true, false)).dimensions(left + 6, y, 60, 14).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Everyone"), b -> pick(true, true)).dimensions(left + 68, y, 52, 14).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("None"), b -> PICKED.clear()).dimensions(left + 122, y, 40, 14).build());
		listTop = y + 18;
		int by = listTop + ROWS * ROW + 6;
		checkButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
			checkIndex = (checkIndex + 1) % checks.size();
			refresh();
		}).dimensions(left + 44, by, W - 50, 14).build());
		dcBox = new TextFieldWidget(textRenderer, left + 44, by + 18, 50, 14, Text.literal("DC"));
		dcBox.setMaxLength(3);
		dcBox.setTextPredicate(t -> t.matches("\\d*"));
		dcBox.setText(dcText);
		dcBox.setChangedListener(t -> dcText = t);
		addDrawableChild(dcBox);
		showButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
			showDc = !showDc;
			refresh();
		}).dimensions(left + 100, by + 18, W - 106, 14).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Request"), b -> send()).dimensions(left + 6, by + 38, 100, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(left + W - 66, by + 38, 60, 16).build());
		refresh();
	}

	private void refresh() {
		checkButton.setMessage(Text.literal(checks.get(checkIndex)[1]));
		showButton.setMessage(Text.literal(showDc ? "Players see the DC" : "DC hidden"));
	}

	private void pick(boolean players, boolean actors) {
		for (Who w : targets) if (w.tag().equals("player") || actors) PICKED.add(w.entityId());
	}

	private void send() {
		if (PICKED.isEmpty()) {
			note = "Pick who to ask.";
			return;
		}
		int dc = dcText.isBlank() ? -1 : Integer.parseInt(dcText.trim());
		ClientPlayNetworking.send(new RollRequestPayload(checks.get(checkIndex)[0], dc, showDc, new ArrayList<>(PICKED)));
		note = "Sent to " + PICKED.size() + ".";
	}

	@Override
	public void render(DrawContext g, int mouseX, int mouseY, float delta) {
		renderBackground(g, mouseX, mouseY, delta);
		int h = (listTop + ROWS * ROW + 66) - top;
		CardStyle.frame(g, left, top, W, h, CardStyle.STEEL);
		g.drawText(textRenderer, "Roll request", left + 6, top + 7, CardStyle.WHITE, true);
		g.fill(left + 6, listTop - 1, left + W - 6, listTop + ROWS * ROW + 1, CardStyle.EDGE);
		g.fill(left + 7, listTop, left + W - 7, listTop + ROWS * ROW, 0xFF0F1117);
		if (targets.isEmpty()) g.drawText(textRenderer, "Nobody to ask yet (players online, Actors in the world).", left + 10, listTop + 4, CardStyle.DIM, false);
		int max = Math.max(0, targets.size() - ROWS);
		scroll = Math.max(0, Math.min(scroll, max));
		for (int i = 0; i < ROWS && scroll + i < targets.size(); i++) {
			Who w = targets.get(scroll + i);
			int y = listTop + i * ROW;
			boolean over = mouseX >= left + 7 && mouseX < left + W - 7 && mouseY >= y && mouseY < y + ROW;
			if (over) g.fill(left + 7, y, left + W - 7, y + ROW, CardStyle.BUTTON_HOVER);
			boolean on = PICKED.contains(w.entityId());
			g.fill(left + 10, y + 2, left + 19, y + 11, on ? 0xFFE0A526 : 0xFF5A616C);
			if (!on) g.fill(left + 11, y + 3, left + 18, y + 10, 0xFF0F1117);
			g.drawText(textRenderer, textRenderer.trimToWidth(w.name(), W - 90), left + 24, y + 3, on ? CardStyle.WHITE : CardStyle.MUTED, false);
			g.drawText(textRenderer, w.tag(), left + W - 12 - textRenderer.getWidth(w.tag()), y + 3, CardStyle.DIM, false);
		}
		if (max > 0) {
			int barH = Math.max(8, Math.round(ROWS * ROW * (ROWS / (float) targets.size())));
			int barY = listTop + Math.round((ROWS * ROW - barH) * (scroll / (float) max));
			g.fill(left + W - 9, barY, left + W - 8, barY + barH, 0xFF5A616C);
		}
		int by = listTop + ROWS * ROW + 6;
		g.drawText(textRenderer, "Roll", left + 8, by + 3, CardStyle.MUTED, false);
		g.drawText(textRenderer, "DC", left + 8, by + 21, CardStyle.MUTED, false);
		if (!note.isEmpty()) g.drawText(textRenderer, note, left + 112, by + 42, CardStyle.MUTED, false);
		super.render(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (button != 0 || mx < left + 7 || mx >= left + W - 7 || my < listTop || my >= listTop + ROWS * ROW) return false;
		int i = scroll + (int) ((my - listTop) / ROW);
		if (i < targets.size()) {
			int id = targets.get(i).entityId();
			if (!PICKED.remove(id)) PICKED.add(id);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll -= (int) Math.signum(v);
		return true;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
