package dev.tacticalcombat.combat;

import dev.tacticalcombat.net.FloatTextPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Sends the numbers that float over a creature when it is hurt, healed or gains something. */
public final class Floaters {
	private Floaters() {}

	private static void send(LivingEntity who, FloatTextPayload payload) {
		if (who == null || !(who.getWorld() instanceof net.minecraft.server.world.ServerWorld w)) return;
		for (ServerPlayerEntity p : PlayerLookup.around(w, who.getPos(), 96)) {
			if (ServerPlayNetworking.canSend(p, FloatTextPayload.ID)) ServerPlayNetworking.send(p, payload);
		}
	}

	/** Health lost; {@code max} (<= 0 when unknown) gives the share of it for the shade of red. */
	public static void damage(LivingEntity who, double amount, double max) {
		if (who == null || amount <= 0) return;
		float pct = max > 0 ? (float) Math.min(1, amount / max) : 0.2f;
		send(who, new FloatTextPayload(who.getId(), 0, "-" + Math.round(amount), pct, 0));
	}

	public static void heal(LivingEntity who, double amount) {
		if (who == null || amount <= 0) return;
		send(who, new FloatTextPayload(who.getId(), 1, "+" + Math.round(amount), 0, 0));
	}

	/** A non-health bar went up or down by {@code delta}. */
	public static void resource(LivingEntity who, String label, double delta, int color) {
		if (who == null || delta == 0) return;
		send(who, new FloatTextPayload(who.getId(), 2, (delta > 0 ? "+" : "") + Math.round(delta) + " " + label, 0, color));
	}

	public static void status(LivingEntity who, String name, boolean gained) {
		if (who == null || name == null || name.isBlank()) return;
		send(who, new FloatTextPayload(who.getId(), gained ? 3 : 4, gained ? name : "-" + name, 0, 0));
	}
}
