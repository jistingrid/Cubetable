package dev.tacticalcombat.card;

import dev.tacticalcombat.net.ShareCardPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Server side of "show": checks the size, puts the sender's real name on it and relays it to every player with the mod. */
public final class CardService {
	private static final long MIN_GAP_MS = 1000;
	private static final AtomicLong NEXT_ID = new AtomicLong(System.currentTimeMillis() % 1_000_000L * 1000L);
	private static final Map<UUID, Long> LAST = new HashMap<>();

	private CardService() {}

	public static void share(ServerPlayerEntity player, ShareCard card) {
		long now = System.currentTimeMillis();
		Long last = LAST.get(player.getUuid());
		if (last != null && now - last < MIN_GAP_MS) return;
		LAST.put(player.getUuid(), now);

		ShareCardPayload payload = new ShareCardPayload(NEXT_ID.incrementAndGet(), player.getGameProfile().getName(), card.cleaned());
		for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, ShareCardPayload.ID)) ServerPlayNetworking.send(p, payload);
		}
	}

	public static void forget(UUID player) {
		LAST.remove(player);
	}
}
