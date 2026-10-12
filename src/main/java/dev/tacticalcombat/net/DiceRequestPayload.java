package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.dice.DiceType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server: "roll this for me" (sent by the character sheet). mode: 0 normal, 1 advantage, 2 disadvantage.
 * kind: 0 a plain roll, 1 an attack roll against the player's target, 2 a damage roll for the player's target.
 * cost: what using the action costs, such as "action:1" (several separated by commas); spent on the acting combatant.
 * crit: the roll's critical rule ({@link dev.tacticalcombat.dice.CritSpec} text), empty when it has none.
 * force: for a damage roll with a critical rule, 0 = critical when the last attack on that target was, 1 = critical,
 * 2 = not critical.
 * rules: the rules text of the entry being rolled (a weapon's "on hit ..."), see {@link dev.tacticalcombat.sheet.ConditionRules}.
 */
public record DiceRequestPayload(String label, DiceType type, int count, int modifier, int mode, int kind, String cost,
								 String crit, int force, String rules, String ref) implements CustomPayload {
	/** A roll whose entry has rules but no row reference. */
	public DiceRequestPayload(String label, DiceType type, int count, int modifier, int mode, int kind, String cost,
							  String crit, int force, String rules) {
		this(label, type, count, modifier, mode, kind, cost, crit, force, rules, "");
	}

	/** A roll with no critical rule. */
	public DiceRequestPayload(String label, DiceType type, int count, int modifier, int mode, int kind, String cost) {
		this(label, type, count, modifier, mode, kind, cost, "", 0, "", "");
	}

	/** A roll with no rules on its entry. */
	public DiceRequestPayload(String label, DiceType type, int count, int modifier, int mode, int kind, String cost,
							  String crit, int force) {
		this(label, type, count, modifier, mode, kind, cost, crit, force, "", "");
	}

	public static final Id<DiceRequestPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "dice_request"));

	public static final PacketCodec<RegistryByteBuf, DiceRequestPayload> CODEC = new PacketCodec<>() {
		@Override
		public DiceRequestPayload decode(RegistryByteBuf buf) {
			String label = buf.readString(64);
			int typeIndex = buf.readVarInt();
			DiceType type = DiceType.values()[Math.floorMod(typeIndex, DiceType.values().length)];
			return new DiceRequestPayload(label, type, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readString(96),
					buf.readString(64), buf.readVarInt(), buf.readString(1000), buf.readString(40));
		}

		@Override
		public void encode(RegistryByteBuf buf, DiceRequestPayload p) {
			buf.writeString(p.label, 64);
			buf.writeVarInt(p.type.ordinal());
			buf.writeVarInt(p.count);
			buf.writeVarInt(p.modifier);
			buf.writeVarInt(p.mode);
			buf.writeVarInt(p.kind);
			buf.writeString(p.cost, 96);
			buf.writeString(p.crit, 64);
			buf.writeVarInt(p.force);
			buf.writeString(p.rules, 1000);
			buf.writeString(p.ref, 40);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
