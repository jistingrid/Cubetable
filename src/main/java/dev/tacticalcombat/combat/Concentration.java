package dev.tacticalcombat.combat;

import dev.tacticalcombat.sheet.CombatRules;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Effects tied to their source. A creature that concentrates on an entry (a rule with the effect Concentrate) remembers
 * every condition that entry then adds, to anyone. The concentration ends, and takes all of them with it, when the creature
 * concentrates on something else, drops it (the effect Stop concentrating, or removing the pack's concentration condition
 * from its sheet), goes down, or fails the check the pack asks for when it is damaged ({@code combat.concentration}).
 * Kept in memory: a server restart forgets it (the conditions stay on the sheets).
 */
public final class Concentration {
	private record Applied(UUID target, String condition) {}

	private static final class Active {
		final String source;
		final int tick;
		final List<Applied> applied = new ArrayList<>();

		Active(String source, int tick) {
			this.source = source;
			this.tick = tick;
		}
	}

	private static final Map<UUID, Active> ACTIVE = new HashMap<>();

	private Concentration() {}

	/** The creature starts concentrating on {@code source}, ending whatever it concentrated on before. */
	public static void begin(MinecraftServer server, LivingEntity caster, String source) {
		Active old = ACTIVE.get(caster.getUuid());
		if (old != null && old.source.equals(source) && old.tick == server.getTicks()) return; // the same cast reaching several targets
		if (old != null) end(server, caster, "it starts concentrating on " + source);
		ACTIVE.put(caster.getUuid(), new Active(source, server.getTicks()));
		CombatRules.Concentration rule = Conditions.concentrationRule(caster);
		if (rule != null && !rule.condition().isEmpty()) Conditions.applyCondition(server, caster, rule.condition(), source);
		server.getPlayerManager().broadcast(Text.literal(Damage.nameOf(caster) + " concentrates on " + source + "."), false);
	}

	/** A condition that {@code source} (an entry of {@code caster}) added to {@code target}: tied to the concentration, if there is one on it. */
	public static void track(LivingEntity caster, String source, LivingEntity target, String condition) {
		Active a = ACTIVE.get(caster.getUuid());
		if (a != null && a.source.equals(source) && !condition.isEmpty()) a.applied.add(new Applied(target.getUuid(), condition));
	}

	public static boolean isConcentrating(LivingEntity who) {
		return ACTIVE.containsKey(who.getUuid());
	}

	/** Ends the concentration and removes everything it added. {@code why} is told in chat. */
	public static void end(MinecraftServer server, LivingEntity caster, String why) {
		Active a = ACTIVE.remove(caster.getUuid());
		if (a == null) return;
		server.getPlayerManager().broadcast(Text.literal(Damage.nameOf(caster) + "'s concentration on " + a.source + " ends (" + why + ")."), false);
		CombatRules.Concentration rule = Conditions.concentrationRule(caster);
		if (rule != null && !rule.condition().isEmpty()) Conditions.dropCondition(server, caster, rule.condition());
		for (Applied p : a.applied) {
			Entity e = find(server, p.target());
			if (e instanceof LivingEntity le) Conditions.dropCondition(server, le, p.condition());
		}
	}

	/** The creature took damage: if it concentrates, the pack's check decides whether it keeps it. */
	public static void onDamaged(MinecraftServer server, LivingEntity who, int damage) {
		Active a = ACTIVE.get(who.getUuid());
		if (a == null || damage <= 0) return;
		CombatRules.Concentration rule = Conditions.concentrationRule(who);
		if (rule == null || rule.check().isEmpty()) return; // the game has no check for it: only the other ways end it
		double dc = Conditions.formula(who, rule.dc(), Map.of("damage", damage));
		if (Double.isNaN(dc)) {
			Conditions.warn(server, "Concentration: the formula '" + rule.dc() + "' does not work for " + Damage.nameOf(who) + ".");
			return;
		}
		final String source = a.source;
		Checks.request(server, who, "Concentration on " + source, rule.check(), (int) Math.round(dc), true, res -> {
			Active now = ACTIVE.get(who.getUuid());
			if (!res.passed() && now != null && now.source.equals(source)) end(server, who, "failed the check");
		});
	}

	/** The creature went down or died. */
	public static void onDown(MinecraftServer server, LivingEntity who) {
		end(server, who, "it can no longer concentrate");
	}

	/** Every second: a concentrating creature that is gone or dead stops. */
	public static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty() || server.getTicks() % 20 != 0) return;
		for (UUID id : new ArrayList<>(ACTIVE.keySet())) {
			Entity e = find(server, id);
			if (e instanceof LivingEntity le && le.isAlive()) continue;
			Active a = ACTIVE.remove(id);
			if (a == null) continue;
			server.getPlayerManager().broadcast(Text.literal("Concentration on " + a.source + " ends (its caster is gone)."), false);
			for (Applied p : a.applied) {
				if (find(server, p.target()) instanceof LivingEntity t) Conditions.dropCondition(server, t, p.condition());
			}
		}
	}

	private static Entity find(MinecraftServer server, UUID id) {
		for (ServerWorld w : server.getWorlds()) {
			Entity e = w.getEntity(id);
			if (e != null) return e;
		}
		return null;
	}
}
