package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: a prompt about attacks of opportunity. kind 0: the player is about to walk away from engaged creatures
 * (text names them; options are the ways to disengage first); 1: a creature is leaving your reach, make the attack of
 * opportunity or not; 2: you are making it now (subject = the entity id of the creature that attacks, whose action bar
 * is shown), press Done afterwards; 3: nothing is pending any more.
 */
public record OpportunityPromptPayload(int kind, int subject, String text, List<Option> options) implements CustomPayload {
	/** A way to disengage: id (sent back), label, cost text ("1 action"), and whether it can be paid now. */
	public record Option(String id, String label, String cost, boolean usable) {}

	public static final Id<OpportunityPromptPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "opportunity_prompt"));

	public static OpportunityPromptPayload close() {
		return new OpportunityPromptPayload(3, -1, "", List.of());
	}

	public static final PacketCodec<RegistryByteBuf, OpportunityPromptPayload> CODEC = new PacketCodec<>() {
		@Override
		public OpportunityPromptPayload decode(RegistryByteBuf buf) {
			int kind = buf.readVarInt();
			int subject = buf.readInt();
			String text = buf.readString(200);
			int n = buf.readVarInt();
			List<Option> options = new ArrayList<>(n);
			for (int i = 0; i < n; i++) options.add(new Option(buf.readString(64), buf.readString(48), buf.readString(32), buf.readBoolean()));
			return new OpportunityPromptPayload(kind, subject, text, options);
		}

		@Override
		public void encode(RegistryByteBuf buf, OpportunityPromptPayload p) {
			buf.writeVarInt(p.kind);
			buf.writeInt(p.subject);
			buf.writeString(p.text, 200);
			buf.writeVarInt(p.options.size());
			for (Option o : p.options) {
				buf.writeString(o.id, 64);
				buf.writeString(o.label, 48);
				buf.writeString(o.cost, 32);
				buf.writeBoolean(o.usable);
			}
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
