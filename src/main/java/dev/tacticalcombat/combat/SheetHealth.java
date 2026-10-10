package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Actors;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.CharacterSync;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The bars a game tracks for a character (hit points, sanity, stamina ...), read from the player's Active Actor.
 * The format's vital bar replaces Minecraft health: damage lowers the sheet (temporary points first) and the
 * vanilla health bar only shows the percentage that is left. Other bars are tracked and shown, and changed by
 * {@link #change}.
 */
public final class SheetHealth {
	private SheetHealth() {}

	/** Current and maximum of one bar. */
	public record Hp(double now, double max) {}

	/** One tracked bar with its values. */
	public record BarValue(String id, String label, double now, double max, int color, boolean vital) {}

	private record Cached(String entryId, long version, List<BarValue> bars) {}

	private static final Map<UUID, Cached> CACHE = new HashMap<>();

	public static void clear() {
		CACHE.clear();
	}

	/** The character a creature fights with: a player's Active Actor, or the sheet of an Actor body. */
	public static CharacterStore.Entry entryOf(LivingEntity who) {
		return entryFor(who);
	}

	private static CharacterStore.Entry entryFor(LivingEntity who) {
		if (who instanceof ServerPlayerEntity p) return Actors.entryOf(p.getUuid());
		return dev.tacticalcombat.actor.ActorRegistry.sheetEntryOf(who);
	}

	private static SheetFormat formatOf(CharacterStore.Entry e) {
		return SheetLibrary.FORMATS.get(e.json.has("format") ? e.json.get("format").getAsString() : "");
	}

	private static CombatRules.Bar vitalOf(CharacterStore.Entry e) {
		SheetFormat f = formatOf(e);
		return f == null || f.combat == null ? null : f.combat.vital();
	}

	public static int color(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "red" -> 0xFFE04040;
			case "green" -> 0xFF50D060;
			case "blue" -> 0xFF4DA3FF;
			case "purple" -> 0xFFB48CFF;
			case "gold", "yellow" -> 0xFFE0B84C;
			case "orange" -> 0xFFFF9F2E;
			case "gray", "grey" -> 0xFF9AA0A8;
			default -> {
				try {
					yield 0xFF000000 | Integer.parseInt(name.replace("#", ""), 16);
				} catch (NumberFormatException ex) {
					yield 0xFF4DA3FF;
				}
			}
		};
	}

	private static double stored(SheetFormat f, CharacterData c, String key) {
		return c.values.getOrDefault(key, f.defaults.getOrDefault(key, 0.0));
	}

	/** Pure calculation: every tracked bar of a sheet. */
	public static List<BarValue> readAll(SheetFormat format, CharacterData c) {
		List<BarValue> out = new ArrayList<>();
		if (format.combat == null) return out;
		SheetContext sc = new SheetContext(format, c);
		for (CombatRules.Bar b : format.combat.bars) {
			double now = stored(format, c, b.now());
			double max = b.max().isBlank() ? Math.max(now, 1) : sc.number(b.max());
			out.add(new BarValue(b.id(), b.label(), now, max, color(b.color()), b.vital()));
		}
		return out;
	}

	/** Pure calculation: the health bar of a sheet. */
	public static Hp read(SheetFormat format, CharacterData c) {
		for (BarValue b : readAll(format, c)) if (b.vital()) return new Hp(b.now(), b.max());
		return null;
	}

	/** Every tracked bar of the player's Active Actor (or an Actor body's sheet); empty when there is none. */
	public static List<BarValue> barsOf(LivingEntity player) {
		CharacterStore.Entry e = entryFor(player);
		SheetFormat f = e == null ? null : formatOf(e);
		if (f == null || f.combat == null || f.combat.bars.isEmpty()) {
			CACHE.remove(player.getUuid());
			return List.of();
		}
		Cached c = CACHE.get(player.getUuid());
		if (c != null && c.entryId().equals(e.id) && c.version() == e.version) return c.bars();
		try {
			List<BarValue> bars = readAll(f, CharacterData.parse(e.json.deepCopy(), ""));
			CACHE.put(player.getUuid(), new Cached(e.id, e.version, bars));
			return bars;
		} catch (RuntimeException ex) {
			return List.of();
		}
	}

	/** The player's hit points from their Active Actor, or null when their game has no health bar there. */
	public static Hp of(LivingEntity player) {
		for (BarValue b : barsOf(player)) if (b.vital()) return new Hp(b.now(), b.max());
		return null;
	}

	/**
	 * Changes one bar of the player's Active Actor: a negative amount is a loss (temporary points go first), a
	 * positive one a gain up to the maximum. Saved on the server and sent to the owner's sheet. Returns false when
	 * the player has no such bar.
	 */
	public static boolean change(MinecraftServer server, LivingEntity player, String barId, double amount) {
		CharacterStore.Entry e = entryFor(player);
		SheetFormat f = e == null ? null : formatOf(e);
		if (f == null || f.combat == null) return false;
		CombatRules.Bar bar = null;
		for (CombatRules.Bar b : f.combat.bars) if (b.id().equalsIgnoreCase(barId)) bar = b;
		if (bar == null) return false;
		CharacterData c;
		try {
			c = CharacterData.parse(e.json.deepCopy(), "");
		} catch (RuntimeException ex) {
			return false;
		}
		double now = stored(f, c, bar.now());
		if (amount < 0) {
			double loss = -amount;
			if (!bar.temp().isBlank()) {
				double temp = stored(f, c, bar.temp());
				double soaked = Math.min(temp, loss);
				if (soaked > 0) c.values.put(bar.temp(), temp - soaked);
				loss -= soaked;
			}
			c.values.put(bar.now(), Math.max(0, now - loss));
		} else {
			double max = bar.max().isBlank() ? Double.MAX_VALUE : new SheetContext(f, c).number(bar.max());
			c.values.put(bar.now(), Math.min(Math.max(max, now), now + amount));
		}
		com.google.gson.JsonObject json = c.toJson();
		json.addProperty("id", e.id);
		e.json = json;
		CharacterSync.changedByServer(server, e);
		return true;
	}

	/** Id of the health bar of the player's Active Actor, or null when there is none. */
	public static String vitalId(LivingEntity player) {
		CharacterStore.Entry e = entryFor(player);
		CombatRules.Bar vital = e == null ? null : vitalOf(e);
		return vital == null ? null : vital.id();
	}

	/** Damage to the character's health bar. */
	public static boolean damage(MinecraftServer server, LivingEntity player, double amount) {
		CharacterStore.Entry e = entryFor(player);
		CombatRules.Bar vital = e == null ? null : vitalOf(e);
		return vital != null && change(server, player, vital.id(), -amount);
	}

	/** ALLOW_DAMAGE hook: damage to a player with a sheet health bar goes to the sheet, not to Minecraft health. */
	public static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
		if (!(victim instanceof ServerPlayerEntity player) || amount <= 0 || player.getServer() == null) return true;
		if (of(player) == null) return true;
		if (damage(player.getServer(), player, Math.max(1, Math.round(amount)))) {
			player.getWorld().sendEntityStatus(player, (byte) 2); // the usual hurt flash and sound, without the damage
			syncBar(player);
			return false;
		}
		return true;
	}

	/** The vanilla health bar shows the share of the sheet's hit points that is left (never fully empty). */
	public static void syncBar(ServerPlayerEntity player) {
		Hp hp = of(player);
		if (hp == null || !player.isAlive()) return;
		double frac = hp.max() <= 0 ? 0 : Math.max(0, Math.min(1, hp.now() / hp.max()));
		float target = (float) Math.max(1.0, frac * player.getMaxHealth());
		if (Math.abs(player.getHealth() - target) > 0.01f) player.setHealth(target);
	}

	/** Every second: keep the bars in step with the sheets (a DM may have healed someone, a rest may have ended ...). */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) syncBar(p);
	}
}
