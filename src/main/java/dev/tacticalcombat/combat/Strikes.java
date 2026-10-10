package dev.tacticalcombat.combat;

import dev.tacticalcombat.dice.DiceService;
import dev.tacticalcombat.dice.DiceType;
import dev.tacticalcombat.sheet.CombatRules;
import dev.tacticalcombat.sheet.SheetContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Rolls aimed at a target: an attack roll is compared with the target's defense (the format's {@code combat.defense}
 * evaluated on the target's sheet) and the result is printed under the roll; a damage roll goes to the owner of the
 * target as a prompt ({@link Damage}). Rolls with no target picked are plain rolls plus a reminder.
 */
public final class Strikes {
	public static final int PLAIN = 0;
	public static final int ATTACK = 1;
	public static final int DAMAGE = 2;

	private Strikes() {}

	public static void roll(ServerPlayerEntity player, String label, DiceType type, int count, int modifier, int mode, int kind) {
		Combat combat = kind == PLAIN ? null : CombatManager.get(player);
		LivingEntity target = combat == null ? null : combat.targetOf(player);
		if (kind == PLAIN || target == null) {
			if (kind != PLAIN) player.sendMessage(Text.translatable("tacticalcombat.msg.no_target"), false);
			DiceService.roll(player, label, type, count, modifier, mode);
			return;
		}
		String name = Damage.nameOf(target);

		if (kind == ATTACK) {
			CombatRules.Defense rule = defenseRule(target);
			Double against = rule == null ? null : defenseValue(target, rule);
			DiceService.rollTotal(player, label, type, count, modifier, mode, total -> {
				if (rule == null || against == null) {
					return new DiceService.Verdict(0, "Target: " + name + " (no defense to check)");
				}
				boolean hit = rule.hits(total, against);
				return new DiceService.Verdict(hit ? 1 : 2, total + " vs " + rule.label() + " "
						+ SheetContext.format(against, false) + ": " + (hit ? "hits " : "misses ") + name);
			});
			return;
		}

		int total = DiceService.rollTotal(player, label, type, count, modifier, mode);
		Damage.offer(player, target, label, Math.max(0, total));
	}

	private static CombatRules.Defense defenseRule(LivingEntity target) {
		if (!(target instanceof ServerPlayerEntity p)) return null;
		ActorSheet s = ActorSheet.of(p);
		return s == null || s.format().combat == null ? null : s.format().combat.defense;
	}

	private static Double defenseValue(LivingEntity target, CombatRules.Defense rule) {
		if (!(target instanceof ServerPlayerEntity p)) return null;
		ActorSheet s = ActorSheet.of(p);
		if (s == null) return null;
		double v = new SheetContext(s.format(), s.character()).number(rule.value());
		return Double.isNaN(v) ? null : v;
	}
}
