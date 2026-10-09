package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Actors;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.CharacterSync;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player with an Active Actor whose game names its hit point value ({@code combat.hp}) uses that sheet value as
 * their hit points, in and out of combat. Damage lowers the sheet (temporary hit points first) instead of the
 * Minecraft health, and the vanilla health bar only shows the percentage that is left.
 */
public final class SheetHealth {
	private SheetHealth() {}

	/** Current and maximum hit points of a sheet. */
	public record Hp(double now, double max) {}

	private record Cached(String entryId, long version, Hp hp) {}

	private static final Map<UUID, Cached> CACHE = new HashMap<>();

	public static void clear() {
		CACHE.clear();
	}

	private static CombatRules.Hp rulesOf(CharacterStore.Entry e) {
		SheetFormat f = SheetLibrary.FORMATS.get(e.json.has("format") ? e.json.get("format").getAsString() : "");
		return f == null || f.combat == null ? null : f.combat.hp;
	}

	/** Pure calculation on a sheet. */
	public static Hp read(SheetFormat format, CharacterData c) {
		CombatRules.Hp rules = format.combat.hp;
		SheetContext sc = new SheetContext(format, c);
		double now = c.values.getOrDefault(rules.now(), format.defaults.getOrDefault(rules.now(), 0.0));
		double max = rules.max().isBlank() ? Math.max(now, 1) : sc.number(rules.max());
		return new Hp(now, max);
	}

	/** The player's hit points from their Active Actor, or null when they have none (Minecraft health then applies). */
	public static Hp of(ServerPlayerEntity player) {
		CharacterStore.Entry e = Actors.entryOf(player.getUuid());
		if (e == null || rulesOf(e) == null) {
			CACHE.remove(player.getUuid());
			return null;
		}
		Cached c = CACHE.get(player.getUuid());
		if (c != null && c.entryId().equals(e.id) && c.version() == e.version) return c.hp();
		try {
			SheetFormat f = SheetLibrary.FORMATS.get(e.json.get("format").getAsString());
			Hp hp = read(f, CharacterData.parse(e.json.deepCopy(), ""));
			CACHE.put(player.getUuid(), new Cached(e.id, e.version, hp));
			return hp;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** Takes hit points off the sheet; temporary hit points are used up first. Returns false when this player has no sheet hp. */
	public static boolean damage(MinecraftServer server, ServerPlayerEntity player, double amount) {
		CharacterStore.Entry e = Actors.entryOf(player.getUuid());
		if (e == null || rulesOf(e) == null) return false;
		SheetFormat f = SheetLibrary.FORMATS.get(e.json.get("format").getAsString());
		CombatRules.Hp rules = f.combat.hp;
		CharacterData c;
		try {
			c = CharacterData.parse(e.json.deepCopy(), "");
		} catch (RuntimeException ex) {
			return false;
		}
		double left = amount;
		if (!rules.temp().isBlank()) {
			double temp = c.values.getOrDefault(rules.temp(), f.defaults.getOrDefault(rules.temp(), 0.0));
			double soaked = Math.min(temp, left);
			if (soaked > 0) c.values.put(rules.temp(), temp - soaked);
			left -= soaked;
		}
		if (left > 0) {
			double now = c.values.getOrDefault(rules.now(), f.defaults.getOrDefault(rules.now(), 0.0));
			c.values.put(rules.now(), Math.max(0, now - left));
		}
		com.google.gson.JsonObject json = c.toJson();
		json.addProperty("id", e.id);
		e.json = json;
		CharacterSync.changedByServer(server, e);
		return true;
	}

	/** ALLOW_DAMAGE hook: damage to a player with sheet hit points goes to the sheet, not to Minecraft health. */
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
