package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.dice.CritSpec;
import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rolls made from the action bar or the sheet, aimed at a target.
 *
 * <p>Who rolls: the player's own character, or, for a Dungeon Master who walks creatures by hand, the creature whose
 * turn it is (the numbers then come from that creature's sheet, which the DM's client evaluates). An attack roll is
 * compared with the target's defense (the format's {@code combat.defense} on the target's sheet: a player's Active
 * Actor or a creature's NPC sheet) and the result is printed under the roll; a damage roll goes to the owner of the
 * target as a prompt ({@link Damage}). A roll that carries a cost spends it from the acting combatant first.
 *
 * <p><b>Criticals.</b> Only a roll that comes with a {@link CritSpec} knows about them. A critical attack (the natural
 * roll reached the sheet's critical range) always hits, a fumble always misses, and the chat says so; a critical hit on
 * a target also marks the player's next damage roll on that target as critical, which a damage roll with a critical
 * rule then applies (Shift forces it, Ctrl rolls it normally). Damage dice without a critical rule never ask.
 */
public final class Strikes {
	public static final int PLAIN = 0;
	public static final int ATTACK = 1;
	public static final int DAMAGE = 2;

	/** A critical hit that has not been followed by its damage roll yet. */
	private record Pending(UUID target, int tick) {}

	/** A critical hit is remembered for this long (two minutes of ticks). */
	private static final int PENDING_TICKS = 2400;
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private Strikes() {}

	public static void roll(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode,
							int kind, String cost) {
		roll(player, label, type, count, modifier, mode, kind, cost, "", 0);
	}

	public static void roll(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode,
							int kind, String cost, String critText, int force) {
		CritSpec spec = CritSpec.parse(critText);
		Combat combat = CombatManager.get(player);
		if (combat == null && Roles.isDm(player.getUuid())) combat = CombatManager.primary();
		Combatant actor = combat == null ? null : combat.actorFor(player);
		boolean asCreature = actor != null && actor.entity != player;
		String roller = asCreature ? Damage.nameOf(actor.entity) : null;

		Map<String, Integer> costs = parseCost(cost);
		if (!costs.isEmpty() && combat != null) {
			if (actor == null || combat.current() != actor) {
				player.sendMessage(Text.translatable("tacticalcombat.msg.not_your_turn"), true);
				return;
			}
			if (!actor.canPay(costs)) {
				player.sendMessage(Text.translatable("tacticalcombat.msg.no_action"), true);
				return;
			}
			actor.pay(costs);
			combat.sync();
		}

		LivingEntity target = combat == null || kind == PLAIN ? null : combat.targetOf(player);
		int now = player.getServer() == null ? 0 : player.getServer().getTicks();

		// roll rules of conditions: the roller's own ("I roll", "I attack") and the target's ("I'm attacked")
		if (kind != DAMAGE && player.getServer() != null) {
			LivingEntity self = asCreature ? actor.entity : player;
			int distance = target == null ? -1 : Math.max(Math.abs(self.getBlockX() - target.getBlockX()), Math.abs(self.getBlockZ() - target.getBlockZ()));
			Conditions.RollEffect own = Conditions.forRoll(self, kind == ATTACK ? java.util.List.of("roll", "attack") : java.util.List.of("roll"), distance);
			Conditions.RollEffect theirs = kind == ATTACK && target != null ? Conditions.forRoll(target, java.util.List.of("attacked"), distance) : Conditions.RollEffect.NONE;
			int adv = own.advantage() + theirs.advantage() + (mode == 1 ? 1 : 0);
			int dis = own.disadvantage() + theirs.disadvantage() + (mode == 2 ? 1 : 0);
			boolean single = type == DiceType.D20 && count == 1;
			if (single && (own.advantage() + own.disadvantage() + theirs.advantage() + theirs.disadvantage()) > 0) {
				mode = adv > 0 && dis == 0 ? 1 : dis > 0 && adv == 0 ? 2 : 0; // one of each cancels out
			}
			modifier += own.bonus() + theirs.bonus();
			java.util.List<String> notes = new java.util.ArrayList<>(own.notes());
			for (String n : theirs.notes()) notes.add("target " + n);
			if (!notes.isEmpty()) {
				String who = roller != null ? roller : player.getGameProfile().getName();
				player.getServer().getPlayerManager().broadcast(Text.literal(who + " (" + label + "): " + String.join(", ", notes)), false);
			}
		}

		// critical damage: only for dice that have a critical rule; asked for (force) or earned by the last attack
		boolean critDamage = false;
		if (kind != ATTACK && !spec.attack() && !spec.none()) {
			Pending p = PENDING.get(player.getUuid());
			boolean earned = kind == DAMAGE && target != null && p != null && p.target().equals(target.getUuid()) && now - p.tick() <= PENDING_TICKS;
			critDamage = force == 1 || (force == 0 && earned);
			if (critDamage) {
				int[] cm = spec.damage(type, count, modifier);
				count = cm[0];
				modifier = cm[1];
				label = label + " (critical)";
			}
		}
		if (kind == DAMAGE) PENDING.remove(player.getUuid()); // this damage settles the last critical hit, if any
		final DiceService.RollVerdict damageMark = critDamage ? (t, n) -> new DiceService.Verdict(0, "", 3) : null;

		if (kind == PLAIN || target == null) {
			if (kind != PLAIN) player.sendMessage(Text.translatable("tacticalcombat.msg.no_target"), false);
			DiceService.RollVerdict v = damageMark;
			if (spec.attack()) v = (t, n) -> new DiceService.Verdict(0, "", spec.check(n)); // a critical shows even with no target
			DiceService.rollNatural(player, roller, label, type, count, modifier, mode, v);
			return;
		}
		String name = Damage.nameOf(target);

		if (kind == ATTACK) {
			CombatRules.Defense rule = defenseRule(target);
			Double against = rule == null ? null : defenseValue(target, rule);
			PENDING.remove(player.getUuid()); // a new attack replaces an older critical hit
			final UUID who = player.getUuid();
			final UUID aimed = target.getUuid();
			DiceService.rollNatural(player, roller, label, type, count, modifier, mode, (total, natural) -> {
				int crit = spec.check(natural);
				String against1 = against == null || rule == null ? "" : total + " vs " + rule.label() + " " + SheetContext.format(against, false) + ": ";
				if (crit == 1) {
					if (spec.attack()) PENDING.put(who, new Pending(aimed, now));
					return new DiceService.Verdict(1, against1 + "critical hit on " + name + " - the next damage roll on it is critical", 1);
				}
				if (crit == 2) return new DiceService.Verdict(2, against1 + "fumble, misses " + name, 2);
				if (rule == null || against == null) {
					return new DiceService.Verdict(0, "Target: " + name + " (no defense to check)");
				}
				boolean hit = rule.hits(total, against);
				return new DiceService.Verdict(hit ? 1 : 2, against1 + (hit ? "hits " : "misses ") + name);
			});
			return;
		}

		int total = DiceService.rollNatural(player, roller, label, type, count, modifier, mode, damageMark);
		Damage.offer(player, asCreature ? roller : null, target, label, Math.max(0, total));
	}

	/** "action:1,bonus:1" to a map; anything malformed is ignored. */
	private static Map<String, Integer> parseCost(String text) {
		Map<String, Integer> out = new LinkedHashMap<>();
		if (text == null) return out;
		for (String part : text.split(",")) {
			String[] kv = part.trim().split(":");
			if (kv.length != 2) continue;
			try {
				int n = Integer.parseInt(kv[1].trim());
				if (n > 0 && n <= 20) out.merge(kv[0].trim().toLowerCase(java.util.Locale.ROOT), n, Integer::sum);
			} catch (NumberFormatException e) {
				// ignored
			}
		}
		return out;
	}

	private static ActorSheet sheetOf(LivingEntity target) {
		if (target instanceof ServerPlayerEntity p) return ActorSheet.of(p);
		NpcSheets.Found f = NpcSheets.find(target);
		return f == null ? null : f.sheet();
	}

	private static CombatRules.Defense defenseRule(LivingEntity target) {
		ActorSheet s = sheetOf(target);
		return s == null || s.format().combat == null ? null : s.format().combat.defense;
	}

	private static Double defenseValue(LivingEntity target, CombatRules.Defense rule) {
		ActorSheet s = sheetOf(target);
		if (s == null) return null;
		double v = new SheetContext(s.format(), s.character()).number(rule.value());
		return Double.isNaN(v) ? null : v;
	}
}
