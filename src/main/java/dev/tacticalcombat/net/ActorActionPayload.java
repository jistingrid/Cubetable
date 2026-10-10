package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client -> server, from a Dungeon Master's Actors window. {@code id} is the Actor; {@code a}, {@code b}, {@code c} and
 * {@code n} depend on the op (see the constants).
 */
public record ActorActionPayload(int op, String id, String a, String b, String c, int n) implements CustomPayload {
	public static final Id<ActorActionPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "actor_action"));

	/** a = name, b = sheet id, c = "kind:value", n = bit 0 linked, bits 1-2 disposition. */
	public static final int CREATE = 0;
	public static final int DELETE = 1;
	public static final int DUPLICATE = 2;
	/** Place the Actor where the DM looks. */
	public static final int SPAWN = 3;
	public static final int RECALL = 4;
	/** c = "kind:value". */
	public static final int MODEL = 5;
	/** b = sheet id, n = 1 linked / 0 own copy. */
	public static final int SHEET = 6;
	/** n = 0 hostile, 1 neutral, 2 friendly. */
	public static final int DISPOSITION = 7;
	/** n = 1 the DM controls it. */
	public static final int CONTROL = 8;
	/** a = new name. */
	public static final int RENAME = 9;
	/** Add the placed Actor to the running fight. */
	public static final int FIGHT = 10;
	/** c = "x y z": walk the placed Actor there (outside a fight). */
	public static final int MOVE = 11;
	/** Cancel the automatic turn of the creature on turn and walk it by hand (id empty = whoever is on turn). */
	public static final int TAKEOVER = 12;
	/** Possess the placed Actor (outside a fight): the DM walks as it in first person. */
	public static final int POSSESS = 13;
	/** Stop possessing (id ignored). */
	public static final int RELEASE = 14;

	public static ActorActionPayload of(int op, String id) {
		return new ActorActionPayload(op, id, "", "", "", 0);
	}

	public static final PacketCodec<RegistryByteBuf, ActorActionPayload> CODEC = new PacketCodec<>() {
		@Override
		public ActorActionPayload decode(RegistryByteBuf buf) {
			return new ActorActionPayload(buf.readVarInt(), buf.readString(64), buf.readString(64), buf.readString(64),
					buf.readString(128), buf.readVarInt());
		}

		@Override
		public void encode(RegistryByteBuf buf, ActorActionPayload p) {
			buf.writeVarInt(p.op);
			buf.writeString(p.id, 64);
			buf.writeString(p.a, 64);
			buf.writeString(p.b, 64);
			buf.writeString(p.c, 128);
			buf.writeVarInt(p.n);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
