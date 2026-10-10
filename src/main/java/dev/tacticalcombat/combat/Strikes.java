package dev.tacticalcombat.combat;

import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rolls made from the action bar or the sheet, aimed at a target.
 *
 * <p>Who rolls: the player's own character, or, for a Dungeon Master who walks creatures by hand, the creature whose
 * turn it is (the numbers then come from that creature's sheet, which the DM's client evaluates). An attack roll is
 * compared with the target's defense (the format's {@code combat.defense} on the target's sheet: a player's Active
 * Actor or a creature's NPC sheet) and the result is printed under the roll; a damage roll goes to the owner of the
 * target as a prompt ({@link Damage}). A roll that carries a cost spends it from the acting combatant first.
 */
public final class Strikes {
	public static final int PLAIN = 0;
	public static final int ATTACK = 1;
	public static final int DAMAGE = 2;

	private Strikes() {}

	public static void roll(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode,
							int kind, String cost) {
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
		if (kind == PLAIN || target == null) {
			if (kind != PLAIN) player.sendMessage(Text.translatable("tacticalcombat.msg.no_target"), false);
			DiceService.rollTotal(player, roller, label, type, count, modifier, mode, null);
			return;
		}
		String name = Damage.nameOf(target);

		if (kind == ATTACK) {
			CombatRules.Defense rule = defenseRule(target);
			Double against = rule == null ? null : defenseValue(target, rule);
			DiceService.rollTotal(player, roller, label, type, count, modifier, mode, total -> {
				if (rule == null || against == null) {
					return new DiceService.Verdict(0, "Target: " + name + " (no defense to check)");
				}
				boolean hit = rule.hits(total, against);
				return new DiceService.Verdict(hit ? 1 : 2, total + " vs " + rule.label() + " "
						+ SheetContext.format(against, false) + ": " + (hit ? "hits " : "misses ") + name);
			});
			return;
		}

		int total = DiceService.rollTotal(player, roller, label, type, count, modifier, mode, null);
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
