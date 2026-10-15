package dev.tacticalcombat.combat;

import dev.tacticalcombat.net.AmbiencePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;

/**
 * The Dungeon Master's time of day and weather. A chosen time or weather is held (the sun and clouds stop moving on
 * their own, and the choice is put back if anything changes it) until the DM switches it off again. Holds the
 * overworld. Does not survive a server restart: the game rules are given back when the server stops.
 */
public final class Ambience {
	public static final int NATURAL = 0;
	/** Time modes: the tick of the day each one holds. */
	public static final int MORNING = 1, DAY = 2, SUNSET = 3, NIGHT = 4;
	private static final long[] TIME_OF_DAY = {0, 23000, 6000, 12500, 18000};
	/** Weather modes. */
	public static final int CLEAR = 1, RAIN = 2, STORM = 3;

	private static final int HOLD_TICKS = 1_000_000;

	public static int time;
	public static int weather;
	/** The game rules as they were before the first hold, to give back. */
	private static Boolean daylightBefore;
	private static Boolean weatherBefore;

	private Ambience() {}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sender.sendPacket(new AmbiencePayload(time, weather)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			setTime(server, NATURAL);
			setWeather(server, NATURAL);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			time = NATURAL;
			weather = NATURAL;
			daylightBefore = null;
			weatherBefore = null;
		});
	}

	public static void setTime(MinecraftServer server, int mode) {
		if (mode < 0 || mode >= TIME_OF_DAY.length) return;
		time = mode;
		GameRules.BooleanRule rule = server.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE);
		if (mode == NATURAL) {
			if (daylightBefore != null) rule.set(daylightBefore, server);
			daylightBefore = null;
		} else {
			if (daylightBefore == null) daylightBefore = rule.get();
			rule.set(false, server);
			apply(server);
		}
		broadcast(server);
	}

	public static void setWeather(MinecraftServer server, int mode) {
		if (mode < 0 || mode > STORM) return;
		boolean wasHeld = weather != NATURAL;
		weather = mode;
		GameRules.BooleanRule rule = server.getGameRules().get(GameRules.DO_WEATHER_CYCLE);
		ServerWorld world = server.getOverworld();
		if (mode == NATURAL) {
			if (weatherBefore != null) rule.set(weatherBefore, server);
			weatherBefore = null;
			if (wasHeld) world.setWeather(24000, 0, false, false); // a held storm ends; the sky is clear for a while, then nature takes over
		} else {
			if (weatherBefore == null) weatherBefore = rule.get();
			rule.set(false, server);
			apply(server);
		}
		broadcast(server);
	}

	/** Puts the held time and weather back; cheap, done about once a second. */
	public static void tick(MinecraftServer server) {
		if ((time == NATURAL && weather == NATURAL) || server.getTicks() % 20 != 0) return;
		apply(server);
	}

	private static void apply(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		if (time != NATURAL) {
			long want = TIME_OF_DAY[time];
			long day = Math.floorDiv(world.getTimeOfDay(), 24000L);
			if (Math.floorMod(world.getTimeOfDay(), 24000L) != want) world.setTimeOfDay(day * 24000L + want);
		}
		if (weather != NATURAL) {
			boolean raining = weather != CLEAR;
			boolean thundering = weather == STORM;
			if (world.isRaining() != raining || world.isThundering() != thundering) {
				world.setWeather(raining ? 0 : HOLD_TICKS, raining ? HOLD_TICKS : 0, raining, thundering);
			}
		}
	}

	private static void broadcast(MinecraftServer server) {
		AmbiencePayload payload = new AmbiencePayload(time, weather);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) ServerPlayNetworking.send(p, payload);
	}
}
