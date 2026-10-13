package dev.tacticalcombat.actor;

import dev.tacticalcombat.character.Roles;
import net.minecraft.entity.LivingEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Hidden Actors. A hidden Actor's body is invisible to everyone but the Dungeon Masters, who see it as the pale,
 * see-through ghost Minecraft shows teammates of an invisible creature: the hidden bodies and the DMs sit on one
 * scoreboard team that shows friendly invisibles. (Armor and held items of a body still show, as with any invisible
 * creature; a hidden Actor's floating name is switched off.)
 */
public final class Ghosts {
	public static final String TEAM = "tc_ghosts";

	private Ghosts() {}

	private static Team team(MinecraftServer server) {
		Scoreboard sb = server.getScoreboard();
		Team t = sb.getTeam(TEAM);
		if (t == null) {
			t = sb.addTeam(TEAM);
			t.setShowFriendlyInvisibles(true);
			t.setFriendlyFireAllowed(true);
		}
		return t;
	}

	/** Makes the Actor's body hidden or shown, as its record says. */
	public static void apply(MinecraftServer server, ActorRecord r) {
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		if (body == null) return;
		Scoreboard sb = server.getScoreboard();
		Team t = team(server);
		String member = body.getNameForScoreboard();
		body.setInvisible(r.hidden);
		body.setCustomNameVisible(!r.hidden);
		if (r.hidden) {
			if (sb.getScoreHolderTeam(member) != t) sb.addScoreHolderToTeam(member, t);
		} else if (sb.getScoreHolderTeam(member) == t) {
			sb.removeScoreHolderFromTeam(member, t);
		}
		syncDms(server);
	}

	/** Keeps every Dungeon Master on the ghost team (and nobody else who is a player): called every second. */
	public static void syncDms(MinecraftServer server) {
		boolean any = false;
		for (ActorRecord r : ActorRegistry.all()) if (r.hidden) any = true;
		Scoreboard sb = server.getScoreboard();
		Team t = sb.getTeam(TEAM);
		if (!any && t == null) return;
		t = team(server);
		List<String> remove = new ArrayList<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			String name = p.getNameForScoreboard();
			boolean in = sb.getScoreHolderTeam(name) == t;
			boolean dm = Roles.isDm(p.getUuid());
			if (dm && any && !in) sb.addScoreHolderToTeam(name, t);
			else if (in && !(dm && any)) remove.add(name);
		}
		for (String name : remove) sb.removeScoreHolderFromTeam(name, t);
	}
}
