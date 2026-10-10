package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> everyone: a character or Actor went down, changed state, or got up. Everyone needs it to draw the model
 * lying on the ground; the {@code prompt} flag opens the card for the people who have to answer it.
 *
 * @param status  0 downed, 1 stable, 2 dead, 3 on their feet again
 * @param canRoll this recipient can roll the death save for it right now
 * @param dm      this recipient is a Dungeon Master (gets the stabilize / kill / revive buttons)
 */
public record DownedPayload(int entityId, String name, int status, int successes, int failures, int needSuccesses,
							int needFailures, boolean prompt, boolean canRoll, boolean dm, String label, String saveLabel,
							String note) implements CustomPayload {
	public static final Id<DownedPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "downed"));

	public static final PacketCodec<RegistryByteBuf, DownedPayload> CODEC = new PacketCodec<>() {
		@Override
		public DownedPayload decode(RegistryByteBuf buf) {
			return new DownedPayload(buf.readVarInt(), buf.readString(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
					buf.readString(32), buf.readString(32), buf.readString(160));
		}

		@Override
		public void encode(RegistryByteBuf buf, DownedPayload p) {
			buf.writeVarInt(p.entityId);
			buf.writeString(p.name, 64);
			buf.writeVarInt(p.status);
			buf.writeVarInt(p.successes);
			buf.writeVarInt(p.failures);
			buf.writeVarInt(p.needSuccesses);
			buf.writeVarInt(p.needFailures);
			buf.writeBoolean(p.prompt);
			buf.writeBoolean(p.canRoll);
			buf.writeBoolean(p.dm);
			buf.writeString(p.label, 32);
			buf.writeString(p.saveLabel, 32);
			buf.writeString(p.note, 160);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
