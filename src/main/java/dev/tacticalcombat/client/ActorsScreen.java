package dev.tacticalcombat.client;

import dev.tacticalcombat.net.ActorActionPayload;
import dev.tacticalcombat.net.DmStatePayload;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.SheetLibrary;
import dev.tacticalcombat.sheet.Theme;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Dungeon Master's Actors window. An Actor is a named creature or person of the world that the DM places like a
 * token: it has a sheet, a model (a Minecraft creature, a player skin, an item or a block), a side (hostile, neutral,
 * friendly) and can be controlled by the DM in a fight. The list is on the left, the selected Actor (or the form for a
 * new one) on the right. Everything is done on the server; this window only sends requests.
 */
public final class ActorsScreen extends Screen {
	private static final int W = 460;
	private static final int H = 280;
	private static final int ROW_H = 24;
	private static final String[] KINDS = {"mob", "skin", "item", "block"};
	private static final String[] KIND_LABEL = {"Creature", "Skin", "Item", "Block"};
	private static final String[] KIND_HINT = {"minecraft:wolf", "player name or texture id", "minecraft:diamond_sword", "minecraft:chest"};
	private static final String[] SIDES = {"Hostile", "Neutral", "Friendly"};

	private static boolean pendingOpen;
	private static String selectedId = "";

	private final Screen back;
	private final int bg, panel, panelHover, edge, gold, muted, dim;

	private boolean creating;
	private int scroll;
	private boolean deleteArmed;
	private long deleteArmedAt;

	// form state (survives a rebuild of the widgets)
	private String nameText = "";
	private String valueText = "";
	private int kindIdx;
	private int sheetIdx;
	private boolean linked;
	private int side;
	private String loadedFor = "";

	private TextFieldWidget nameField;
	private TextFieldWidget valueField;

	private int px, py;

	public ActorsScreen(Screen back) {
		super(Text.empty());
		this.back = back;
		Theme t = SheetLibrary.theme("");
		bg = t.get("bg");
		panel = t.get("panel");
		panelHover = t.get("panel_hover");
		edge = t.get("edge");
		gold = t.get("accent");
		muted = t.get("muted");
		dim = t.get("dim");
	}

	/** From /actors: opens on the next client tick, once the chat has closed. */
	public static void requestOpen() {
		pendingOpen = true;
	}

	public static void tick(MinecraftClient client) {
		if (!pendingOpen || client.player == null) return;
		if (client.currentScreen == null || client.currentScreen instanceof TacticalScreen) {
			pendingOpen = false;
			if (ServerCharacters.isDm()) client.setScreen(new ActorsScreen(null));
			else client.player.sendMessage(Text.literal("Only a Dungeon Master has the Actors window."), false);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// the battlefield stays visible behind the window
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(back);
	}

	// ------------------------------------------------------------------ data

	private DmStatePayload.ActorInfo selected() {
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.id().equals(selectedId)) return a;
		return null;
	}

	/** Sheets an Actor can use: every character the server told this DM about, except the private copies of Actors. */
	private List<CharacterData> sheets() {
		Set<String> owned = new HashSet<>();
		for (DmStatePayload.ActorInfo a : DmState.actors) if (a.ownsSheet()) owned.add(a.sheetId());
		List<CharacterData> out = new ArrayList<>();
		for (CharacterData c : SheetLibrary.CHARACTERS) if (!c.remote && !c.link.isEmpty() && !owned.contains(c.link)) out.add(c);
		for (CharacterData c : ServerCharacters.remotes()) if (!c.link.isEmpty() && !owned.contains(c.link)) out.add(c);
		out.sort((a, b) -> {
			int k = Boolean.compare(!"npc".equals(a.kind), !"npc".equals(b.kind));
			return k != 0 ? k : a.displayName().compareToIgnoreCase(b.displayName());
		});
		return out;
	}

	private String sheetName(String id) {
		CharacterData c = CharacterSheetScreen.find(id, "");
		return c == null ? "(unknown sheet)" : c.displayName();
	}

	private static void send(ActorActionPayload p) {
		ClientPlayNetworking.send(p);
	}

	private static String modelText(int kind, String value) {
		return KINDS[kind] + ":" + value.trim();
	}

	private static int kindIndex(String kind) {
		for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
		return 0;
	}

	// ------------------------------------------------------------------ widgets

	@Override
	protected void init() {
		int w = Math.min(W, width - 8);
		int h = Math.min(H, height - 8);
		px = (width - w) / 2;
		py = (height - h) / 2;
		nameField = null;
		valueField = null;

		int rx = px + 166;
		int rw = w - 174;
		int y = py + 46;

		addDrawableChild(ButtonWidget.builder(Text.literal("New actor"), b -> {
			creating = true;
			nameText = "";
			valueText = "";
			kindIdx = 0;
			side = 0;
			linked = false;
			sheetIdx = 0;
			clearAndInit();
		}).dimensions(px + 8, py + h - 24, 150, 16).build());

		if (creating) {
			nameField = field(rx + 44, y, rw - 44, "Name", nameText, s -> nameText = s);
			y += 22;
			List<CharacterData> sheets = sheets();
			sheetIdx = sheets.isEmpty() ? 0 : MathHelper.clamp(sheetIdx, 0, sheets.size() - 1);
			String sheetLabel = sheets.isEmpty() ? "No sheets yet" : sheets.get(sheetIdx).displayName();
			addDrawableChild(ButtonWidget.builder(Text.literal(sheetLabel), b -> {
				if (!sheets.isEmpty()) sheetIdx = (sheetIdx + 1) % sheets.size();
				clearAndInit();
			}).dimensions(rx + 44, y, rw - 44 - 84, 16).build());
			addDrawableChild(ButtonWidget.builder(Text.literal(linked ? "Shared" : "Own copy"), b -> {
				linked = !linked;
				clearAndInit();
			}).dimensions(rx + rw - 80, y, 80, 16).build());
			y += 22;
			modelRow(rx, y, rw);
			y += 22;
			addDrawableChild(ButtonWidget.builder(Text.literal(SIDES[side]), b -> {
				side = (side + 1) % 3;
				clearAndInit();
			}).dimensions(rx + 44, y, 80, 16).build());
			y += 30;
			addDrawableChild(ButtonWidget.builder(Text.literal("Create"), b -> {
				if (sheets.isEmpty()) return;
				send(new ActorActionPayload(ActorActionPayload.CREATE, "", nameText.trim(), sheets.get(sheetIdx).link,
						modelText(kindIdx, valueText), (linked ? 1 : 0) | (side << 1)));
				creating = false;
				selectedId = "";
				clearAndInit();
			}).dimensions(rx, y, 70, 16).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> {
				creating = false;
				clearAndInit();
			}).dimensions(rx + 76, y, 70, 16).build());
			return;
		}

		DmStatePayload.ActorInfo a = selected();
		if (a == null) return;
		if (!loadedFor.equals(a.id())) { // a different Actor: its values go into the fields
			loadedFor = a.id();
			nameText = a.name();
			kindIdx = kindIndex(a.kind());
			valueText = a.value();
			sheetIdx = 0;
			linked = !a.ownsSheet();
			deleteArmed = false;
		}
		final String id = a.id();
		boolean inFight = a.inFight();

		nameField = field(rx + 44, y, rw - 44 - 56, "Name", nameText, s -> nameText = s);
		addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), b -> send(new ActorActionPayload(ActorActionPayload.RENAME, id, nameText.trim(), "", "", 0)))
				.dimensions(rx + rw - 52, y, 52, 16).build());
		y += 22;

		List<CharacterData> sheets = sheets();
		sheetIdx = sheets.isEmpty() ? 0 : MathHelper.clamp(sheetIdx, 0, sheets.size() - 1);
		addDrawableChild(ButtonWidget.builder(Text.literal("Open sheet"), b -> {
			if (client != null) CharacterSheetScreen.open(client, this, a.sheetId(), "");
		}).dimensions(rx + 44, y, 70, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal(sheets.isEmpty() ? "-" : sheets.get(sheetIdx).displayName()), b -> {
			if (!sheets.isEmpty()) sheetIdx = (sheetIdx + 1) % sheets.size();
			clearAndInit();
		}).dimensions(rx + 118, y, rw - 118 - 124, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal(linked ? "Shared" : "Own copy"), b -> {
			linked = !linked;
			clearAndInit();
		}).dimensions(rx + rw - 120, y, 64, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Set"), b -> {
			if (sheets.isEmpty() || inFight) return;
			send(new ActorActionPayload(ActorActionPayload.SHEET, id, "", sheets.get(sheetIdx).link, "", linked ? 1 : 0));
		}).dimensions(rx + rw - 52, y, 52, 16).build());
		y += 22;

		modelRow(rx, y, rw);
		addDrawableChild(ButtonWidget.builder(Text.literal("Apply"), b -> send(new ActorActionPayload(ActorActionPayload.MODEL, id, "", "", modelText(kindIdx, valueText), 0)))
				.dimensions(rx + rw - 52, y, 52, 16).build());
		y += 22;

		addDrawableChild(ButtonWidget.builder(Text.literal(SIDES[a.disposition()]), b ->
				send(new ActorActionPayload(ActorActionPayload.DISPOSITION, id, "", "", "", (a.disposition() + 1) % 3)))
				.dimensions(rx + 44, y, 80, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal(a.dmControl() ? "DM controls it" : "Takes its own turns"), b ->
				send(new ActorActionPayload(ActorActionPayload.CONTROL, id, "", "", "", a.dmControl() ? 0 : 1)))
				.dimensions(rx + 130, y, 120, 16).build());
		y += 30;

		addDrawableChild(ButtonWidget.builder(Text.literal(a.entityId() >= 0 ? "Move here" : "Place"), b ->
				send(ActorActionPayload.of(ActorActionPayload.SPAWN, id))).dimensions(rx, y, 64, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Recall"), b ->
				send(ActorActionPayload.of(ActorActionPayload.RECALL, id))).dimensions(rx + 68, y, 50, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("To fight"), b ->
				send(ActorActionPayload.of(ActorActionPayload.FIGHT, id))).dimensions(rx + 122, y, 56, 16).build());
		y += 22;
		addDrawableChild(ButtonWidget.builder(Text.literal("Duplicate"), b ->
				send(ActorActionPayload.of(ActorActionPayload.DUPLICATE, id))).dimensions(rx, y, 64, 16).build());
		addDrawableChild(ButtonWidget.builder(Text.literal(deleteArmed ? "Really delete?" : "Delete"), b -> {
			if (!deleteArmed) {
				deleteArmed = true;
				deleteArmedAt = System.currentTimeMillis();
				clearAndInit();
				return;
			}
			deleteArmed = false;
			send(ActorActionPayload.of(ActorActionPayload.DELETE, id));
			selectedId = "";
			loadedFor = "";
			clearAndInit();
		}).dimensions(rx + 68, y, 80, 16).build());
	}

	private void modelRow(int rx, int y, int rw) {
		addDrawableChild(ButtonWidget.builder(Text.literal(KIND_LABEL[kindIdx]), b -> {
			kindIdx = (kindIdx + 1) % KINDS.length;
			clearAndInit();
		}).dimensions(rx + 44, y, 62, 16).build());
		valueField = field(rx + 110, y, rw - 110 - (creating ? 0 : 56), KIND_HINT[kindIdx], valueText, s -> valueText = s);
	}

	private TextFieldWidget field(int x, int y, int w, String hint, String text, java.util.function.Consumer<String> changed) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 16, Text.literal(hint));
		f.setMaxLength(100);
		f.setPlaceholder(Text.literal(hint));
		f.setText(text);
		f.setChangedListener(changed);
		addDrawableChild(f);
		return f;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(DrawContext g, int mx, int my, float delta) {
		if (deleteArmed && System.currentTimeMillis() - deleteArmedAt > 3000) {
			deleteArmed = false;
			clearAndInit();
		}
		DmStatePayload.ActorInfo a = creating ? null : selected();
		if (!creating && a != null && !loadedFor.equals(a.id())) clearAndInit();

		int w = Math.min(W, width - 8);
		int h = Math.min(H, height - 8);
		g.fill(px - 2, py - 2, px + w + 2, py + h + 2, 0xFF05060A);
		g.fill(px - 1, py - 1, px + w + 1, py + h + 1, 0xFF3B414C);
		g.fill(px, py, px + w, py + h, bg);
		g.fill(px, py, px + w, py + 16, 0xFF0F1114);
		g.fill(px, py + 15, px + w, py + 16, edge);
		g.drawText(textRenderer, "Actors", px + 6, py + 4, 0xFFC9CCD2, false);
		g.drawText(textRenderer, "Characters of the world that you place and control, like tokens.", px + 50, py + 4, dim, false);

		// list
		int lx = px + 8;
		int ly = py + 24;
		int lw = 150;
		int lh = h - 24 - 30;
		g.fill(lx - 1, ly - 1, lx + lw + 1, ly + lh + 1, edge);
		g.fill(lx, ly, lx + lw, ly + lh, 0xFF0B0C10);
		List<DmStatePayload.ActorInfo> list = DmState.actors;
		int rows = Math.max(1, lh / ROW_H);
		scroll = MathHelper.clamp(scroll, 0, Math.max(0, list.size() - rows));
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		if (list.isEmpty()) g.drawText(textRenderer, "No Actors yet.", lx + 6, ly + 6, dim, false);
		for (int i = 0; i < rows && i + scroll < list.size(); i++) {
			DmStatePayload.ActorInfo e = list.get(i + scroll);
			int ry = ly + i * ROW_H;
			boolean sel = !creating && e.id().equals(selectedId);
			boolean over = mx >= lx && mx < lx + lw && my >= ry && my < ry + ROW_H - 1;
			g.fill(lx, ry, lx + lw, ry + ROW_H - 1, sel ? panelHover : over ? panel : 0xFF11131A);
			int side = e.disposition() == 0 ? 0xFFC83232 : e.disposition() == 1 ? 0xFFC9A227 : 0xFF3CB84A;
			g.fill(lx, ry, lx + 2, ry + ROW_H - 1, side);
			g.drawText(textRenderer, textRenderer.trimToWidth(e.name(), lw - 40), lx + 6, ry + 3, 0xFFE6E8EB, false);
			String tag = e.inFight() ? "fighting" : e.entityId() >= 0 ? "placed" : "not placed";
			g.drawText(textRenderer, tag, lx + lw - textRenderer.getWidth(tag) - 4, ry + 3, e.inFight() ? gold : dim, false);
			float frac = e.max() <= 0 ? 0 : Math.max(0f, Math.min(1f, e.hp() / e.max()));
			g.fill(lx + 6, ry + 15, lx + 6 + 70, ry + 18, 0xFF05060A);
			g.fill(lx + 6, ry + 15, lx + 6 + Math.round(70 * frac), ry + 18, side);
			g.drawText(textRenderer, Math.round(e.hp()) + "/" + Math.round(e.max()), lx + 80, ry + 12, muted, false);
		}
		g.disableScissor();

		// right side
		int rx = px + 166;
		int y = py + 46;
		if (creating) {
			g.drawText(textRenderer, "New actor", rx, py + 26, gold, false);
			label(g, "Name", rx, y + 4);
			label(g, "Sheet", rx, y + 26);
			label(g, "Model", rx, y + 48);
			label(g, "Side", rx, y + 70);
			g.drawText(textRenderer, "Own copy: its hit points are its own. Shared: it uses the character itself.", rx, y + 98, dim, false);
			g.drawText(textRenderer, "Tip: set Own copy for monsters, Shared for named characters.", rx, y + 108, dim, false);
		} else if (a == null) {
			g.drawText(textRenderer, "Pick an Actor on the left, or make a new one.", rx, py + 30, dim, false);
		} else {
			g.drawText(textRenderer, a.name() + "  -  " + sheetName(a.sheetId()) + (a.ownsSheet() ? " (own copy)" : " (shared)"),
					rx, py + 26, gold, false);
			label(g, "Name", rx, y + 4);
			label(g, "Sheet", rx, y + 26);
			label(g, "Model", rx, y + 48);
			label(g, "Side", rx, y + 70);
			g.drawText(textRenderer, "Place puts it where your crosshair points. Model changes keep its position.", rx, y + 118, dim, false);
			g.drawText(textRenderer, "Hostile Actors near the party join when you start an encounter; use To fight for others.", rx, y + 128, dim, false);
			g.drawText(textRenderer, "Shared sheets share hit points between every Actor that uses them.", rx, y + 138, dim, false);
		}
		super.render(g, mx, my, delta);
	}

	private void label(DrawContext g, String text, int x, int y) {
		g.drawText(textRenderer, text, x, y, muted, false);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		int h = Math.min(H, height - 8);
		int lx = px + 8;
		int ly = py + 24;
		int lh = h - 24 - 30;
		if (button == 0 && mx >= lx && mx < lx + 150 && my >= ly && my < ly + lh) {
			int i = (int) ((my - ly) / ROW_H) + scroll;
			if (i >= 0 && i < DmState.actors.size()) {
				selectedId = DmState.actors.get(i).id();
				creating = false;
				loadedFor = "";
				clearAndInit();
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scroll = Math.max(0, scroll - (int) Math.signum(vertical));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		boolean typing = (nameField != null && nameField.isFocused()) || (valueField != null && valueField.isFocused());
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		if (typing) return super.keyPressed(keyCode, scanCode, modifiers); // letters belong to the field, not to key binds
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
