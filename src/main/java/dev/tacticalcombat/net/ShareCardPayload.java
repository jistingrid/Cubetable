package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.card.ShareCard;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> clients: a shared sheet entry, with the id clients use to recall it and the real name of the player who showed it. */
public record ShareCardPayload(long id, String player, ShareCard card) implements CustomPayload {
	public static final Id<ShareCardPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "share_card"));

	public static final PacketCodec<RegistryByteBuf, ShareCardPayload> CODEC = new PacketCodec<>() {
		@Override
		public ShareCardPayload decode(RegistryByteBuf buf) {
			return new ShareCardPayload(buf.readLong(), buf.readString(32), ShareCard.read(buf));
		}

		@Override
		public void encode(RegistryByteBuf buf, ShareCardPayload p) {
			buf.writeLong(p.id);
			buf.writeString(p.player, 32);
			p.card.write(buf);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
