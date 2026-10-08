package dev.tacticalcombat.net;

import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.card.ShareCard;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: "show this sheet entry to everyone". */
public record ShareCardRequestPayload(ShareCard card) implements CustomPayload {
	public static final Id<ShareCardRequestPayload> ID =
			new Id<>(Identifier.of(TacticalCombatMod.MOD_ID, "share_card_request"));

	public static final PacketCodec<RegistryByteBuf, ShareCardRequestPayload> CODEC = new PacketCodec<>() {
		@Override
		public ShareCardRequestPayload decode(RegistryByteBuf buf) {
			return new ShareCardRequestPayload(ShareCard.read(buf));
		}

		@Override
		public void encode(RegistryByteBuf buf, ShareCardRequestPayload p) {
			p.card.write(buf);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
