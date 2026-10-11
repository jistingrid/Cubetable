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
public record ActorActionPayload(int op, String id, String a, String b, String c, int n, String d) implements CustomPayload {
	/** Without the extra text (only CREATE uses it: tags). */
	public ActorActionPayload(int op, String id, String a, String b, String c, int n) {
		this(op, id, a, b, c, n, "");
	}

	public static final Id<ActorActionPayload> ID = new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "actor_action"));

	/** a = name, b = sheet id, c = "kind:value", n = bit 0 linked, bits 1-2 disposition, id = folder ("" none), d = tags. */
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

	/** File the Actor in a folder: a = folder name (made when new), empty = take it out of its folder. */
	public static final int FOLDER = 15;
	/** a = tags, comma separated (replaces its tags). */
	public static final int TAGS = 16;
	/** a = a new, empty folder. */
	public static final int FOLDER_NEW = 17;
	/** a = old name, b = new name. */
	public static final int FOLDER_RENAME = 18;
	/** a = folder name; its Actors go back to the top level. */
	public static final int FOLDER_DELETE = 19;
	/** Turn the placed Actor by n degrees (negative = left). Allowed in a fight. */
	public static final int TURN = 21;
	/** c = "x z": face that point; empty: face the Dungeon Master. */
	public static final int FACE = 22;
	/** Centre it on its block and turn it to the nearest 45 degrees. */
	public static final int SNAP = 23;
	/** c = "dx dy dz": move it by that many blocks (outside a fight). */
	public static final int NUDGE = 24;
	/** Make a copy of the Actor and place it beside the original. */
	public static final int CLONE = 25;
	/** Make an Actor out of a sheet, named after it. id = folder ("" none), b = sheet id, c = "kind:value", n as CREATE, d = tags. */
	public static final int IMPORT = 20;

	public static ActorActionPayload of(int op, String id) {
		return new ActorActionPayload(op, id, "", "", "", 0);
	}

	public static final PacketCodec<RegistryByteBuf, ActorActionPayload> CODEC = new PacketCodec<>() {
		@Override
		public ActorActionPayload decode(RegistryByteBuf buf) {
			return new ActorActionPayload(buf.readVarInt(), buf.readString(64), buf.readString(128), buf.readString(64),
					buf.readString(128), buf.readVarInt(), buf.readString(128));
		}

		@Override
		public void encode(RegistryByteBuf buf, ActorActionPayload p) {
			buf.writeVarInt(p.op);
			buf.writeString(p.id, 64);
			buf.writeString(p.a, 128);
			buf.writeString(p.b, 64);
			buf.writeString(p.c, 128);
			buf.writeVarInt(p.n);
			buf.writeString(p.d, 128);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
