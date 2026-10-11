package dev.tacticalcombat.character;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import dev.tacticalcombat.combat.DmTools;
import dev.tacticalcombat.net.FormatUploadPayload;
import dev.tacticalcombat.sheet.SheetFormat;
import dev.tacticalcombat.sheet.SheetLibrary;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs a game pack format sent by a Dungeon Master into the server's packs folder and reloads the packs. */
public final class FormatUpload {
	private FormatUpload() {}

	public static void receive(ServerPlayerEntity player, FormatUploadPayload p) {
		if (!Roles.isDm(player.getUuid()) || player.getServer() == null) return;
		String id = p.id();
		if (!id.matches("[A-Za-z0-9_\\-]{1,40}")) return;
		try {
			JsonObject json = JsonParser.parseString(Gz.unpack(p.data())).getAsJsonObject();
			SheetFormat f = SheetFormat.parse(json, "sent by " + player.getGameProfile().getName()); // refuses a broken format
			if (!f.id.equals(id)) {
				player.sendMessage(Text.literal("[Packs] The format inside is called " + f.id + ", not " + id + "; not installed."), false);
				return;
			}
			Path dir = SheetLibrary.packsDir().resolve(id);
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("format.json"), new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
			SheetLibrary.reloadPacks();
			TacticalCombatMod.LOGGER.info("Game pack '{}' installed on the server by {}", id, player.getGameProfile().getName());
			player.sendMessage(Text.literal("[Packs] The server now has the game pack " + f.name + "."), false);
			DmTools.broadcast(player.getServer());
		} catch (IOException | RuntimeException e) {
			player.sendMessage(Text.literal("[Packs] The server could not use the game pack " + id + ": " + e.getMessage()), false);
		}
	}
}
