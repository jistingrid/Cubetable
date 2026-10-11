package dev.tacticalcombat.client;

import dev.tacticalcombat.character.Gz;
import dev.tacticalcombat.net.FormatUploadPayload;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A server reads hit points and combat rules from its own copy of the game packs. When it says it has characters of a
 * format it has no pack for, a Dungeon Master's client sends its own copy (once per format and session); the DM tools
 * tab also has a button that sends every pack again, to update one.
 */
final class FormatUploader {
	private static final int MAX_PACKET = 29000;
	private static final Set<String> TRIED = new HashSet<>();

	private FormatUploader() {}

	static void reset() {
		TRIED.clear();
	}

	/** Called with the formats the server lacks; sends the ones this client has. */
	static void offer(List<String> missing) {
		if (missing.isEmpty() || !ServerCharacters.isDm()) return;
		for (String id : missing) {
			if (!TRIED.add(id)) continue;
			SheetFormat f = SheetLibrary.FORMATS.get(id);
			if (f == null || f.json == null) {
				say("The server has characters for the game pack '" + id + "', but you do not have it either. Copy its folder into the server's config/tacticalcombat/packs.", Formatting.RED);
				continue;
			}
			send(f, true);
		}
	}

	/** The DM tools button: sends every pack this client has, replacing the server's copy. */
	static void sendAll() {
		if (!ServerCharacters.isDm()) {
			say("Only a Dungeon Master can send game packs to the server.", Formatting.RED);
			return;
		}
		int n = 0;
		for (SheetFormat f : SheetLibrary.FORMATS.values()) if (f.json != null && send(f, false)) n++;
		if (n == 0) say("There is no game pack to send.", Formatting.GRAY);
	}

	private static boolean send(SheetFormat f, boolean automatic) {
		if (!ClientPlayNetworking.canSend(FormatUploadPayload.ID)) {
			say("This server does not have Tactical Combat, so it can not take game packs.", Formatting.RED);
			return false;
		}
		byte[] data = Gz.pack(f.json.toString());
		if (data.length > MAX_PACKET) {
			say("The game pack " + f.id + " is too big to send (over about 29 KB compressed). Copy its folder into the server's config/tacticalcombat/packs.", Formatting.RED);
			return false;
		}
		ClientPlayNetworking.send(new FormatUploadPayload(f.id, data));
		if (automatic) say("The server had no game pack '" + f.id + "' (hit points and combat rules come from it): sent yours.", Formatting.GRAY);
		return true;
	}

	private static void say(String text, Formatting color) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.sendMessage(Text.literal("[Packs] " + text).formatted(color), false);
	}
}
