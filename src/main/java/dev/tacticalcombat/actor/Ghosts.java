package dev.tacticalcombat.actor;

import dev.tacticalcombat.character.Roles;
import net.minecraft.entity.LivingEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hidden Actors. A hidden Actor's body is invisible to everyone but the Dungeon Masters, who see it as the pale,
 * see-through ghost Minecraft shows teammates of an invisible creature: the hidden bodies and the DMs sit on one
 * scoreboard team that shows friendly invisibles. (Armor and held items of a body still show, as with any invisible
 * creature; a hidden Actor's floating name is switched off.)
 */
public final class Ghosts {
	public static final String TEAM = "tc_ghosts";

	/** Dungeon Masters who made themselves invisible to the players (until the server stops). */
	private static final Set<UUID> INVISIBLE_DMS = new HashSet<>();

	private Ghosts() {}

	public static boolean isInvisibleDm(UUID dm) {
		return INVISIBLE_DMS.contains(dm);
	}

	public static void clear() {
		INVISIBLE_DMS.clear();
	}

	private static StatusEffectInstance ghostEffect() {
		return new StatusEffectInstance(StatusEffects.INVISIBILITY, StatusEffectInstance.INFINITE, 0, false, false, true);
	}

	/** A Dungeon Master hides from or shows themselves to the players; other DMs see a ghost. */
	public static void setDmInvisible(MinecraftServer server, ServerPlayerEntity dm, boolean on) {
		if (!Roles.isDm(dm.getUuid())) return;
		if (on) {
			INVISIBLE_DMS.add(dm.getUuid());
			dm.addStatusEffect(ghostEffect());
		} else {
			INVISIBLE_DMS.remove(dm.getUuid());
			dm.removeStatusEffect(StatusEffects.INVISIBILITY);
		}
		syncDms(server);
	}

	/**
	 * The ghost team, made when missing and brought to the settings it must have every time (a team saved by an earlier
	 * version of the mod may differ): invisible members show to each other, nobody pushes them (a flying Dungeon Master
	 * passes through creatures and players), and name tags follow visibility - a hidden member's name is not shown to
	 * players, as the name of any invisible creature is not.
	 */
	private static Team team(MinecraftServer server) {
		Scoreboard sb = server.getScoreboard();
		Team t = sb.getTeam(TEAM);
		if (t == null) t = sb.addTeam(TEAM);
		if (!t.shouldShowFriendlyInvisibles()) t.setShowFriendlyInvisibles(true);
		if (!t.isFriendlyFireAllowed()) t.setFriendlyFireAllowed(true);
		if (t.getCollisionRule() != AbstractTeam.CollisionRule.NEVER) t.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
		if (t.getNameTagVisibilityRule() != AbstractTeam.VisibilityRule.ALWAYS) t.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.ALWAYS);
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
		if (!INVISIBLE_DMS.isEmpty()) any = true;
		Scoreboard sb = server.getScoreboard();
		Team t = sb.getTeam(TEAM);
		if (!any && t == null) return;
		t = team(server);
		List<String> remove = new ArrayList<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			String name = p.getNameForScoreboard();
			boolean in = sb.getScoreHolderTeam(name) == t;
			boolean dm = Roles.isDm(p.getUuid());
			if (INVISIBLE_DMS.contains(p.getUuid())) {
				if (!dm) { // no longer a Dungeon Master: visible again
					INVISIBLE_DMS.remove(p.getUuid());
					p.removeStatusEffect(StatusEffects.INVISIBILITY);
				} else if (!p.hasStatusEffect(StatusEffects.INVISIBILITY)) {
					p.addStatusEffect(ghostEffect()); // a milk bucket or a respawn took it off
				}
			}
			if (dm && any && !in) sb.addScoreHolderToTeam(name, t);
			else if (in && !(dm && any)) remove.add(name);
		}
		for (String name : remove) sb.removeScoreHolderFromTeam(name, t);
	}
}
