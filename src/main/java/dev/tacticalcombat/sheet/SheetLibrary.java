package dev.tacticalcombat.sheet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.TacticalCombatMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds sheet formats and characters. Formats: built in, plus every .json in config/tacticalcombat/sheets/ (a file
 * with the same id replaces the built in one). Characters: every .json in config/tacticalcombat/characters/.
 * Client side only; nothing here touches the game yet.
 */
public final class SheetLibrary {
	private static final String RES = "/assets/tacticalcombat/sheets/";
	private static final String[] BUILT_IN_FORMATS = {"generic_d20", "percentile"};
	private static final String[][] EXAMPLE_CHARACTERS = {
			{"example_character", "brannoc_veyle.json"}, {"example_character_percentile", "ines_marlowe.json"}};

	public static final Map<String, SheetFormat> FORMATS = new LinkedHashMap<>();
	public static final List<CharacterData> CHARACTERS = new ArrayList<>();
	/** Files that could not be read, with the reason; shown in the window so a typo is easy to find. */
	public static final List<String> PROBLEMS = new ArrayList<>();

	private SheetLibrary() {}

	public static Path sheetsDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("tacticalcombat").resolve("sheets");
	}

	public static Path charactersDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("tacticalcombat").resolve("characters");
	}

	public static void reload() {
		FORMATS.clear();
		CHARACTERS.clear();
		PROBLEMS.clear();

		for (String name : BUILT_IN_FORMATS) {
			try (InputStream in = SheetLibrary.class.getResourceAsStream(RES + name + ".json")) {
				if (in == null) continue;
				SheetFormat f = SheetFormat.parse(readObject(in), "built in");
				FORMATS.put(f.id, f);
			} catch (Exception e) {
				PROBLEMS.add("built-in " + name + ": " + e.getMessage());
			}
		}

		try {
			Files.createDirectories(sheetsDir());
			Files.createDirectories(charactersDir());
			seedExamples();
		} catch (IOException e) {
			PROBLEMS.add("config folder: " + e.getMessage());
		}

		for (Path p : jsonFiles(sheetsDir())) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				SheetFormat f = SheetFormat.parse(JsonParser.parseReader(r).getAsJsonObject(), p.getFileName().toString());
				FORMATS.put(f.id, f);
			} catch (Exception e) {
				PROBLEMS.add(p.getFileName() + ": " + e.getMessage());
			}
		}
		for (Path p : jsonFiles(charactersDir())) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				CHARACTERS.add(CharacterData.parse(JsonParser.parseReader(r).getAsJsonObject(), p.getFileName().toString()));
			} catch (Exception e) {
				PROBLEMS.add(p.getFileName() + ": " + e.getMessage());
			}
		}
		TacticalCombatMod.LOGGER.info("Sheets: {} formats, {} characters, {} problems",
				FORMATS.size(), CHARACTERS.size(), PROBLEMS.size());
	}

	/** First run: drop a couple of example characters into the (empty) characters folder. */
	private static void seedExamples() throws IOException {
		if (!jsonFiles(charactersDir()).isEmpty()) return;
		for (String[] ex : EXAMPLE_CHARACTERS) {
			try (InputStream in = SheetLibrary.class.getResourceAsStream(RES + ex[0] + ".json")) {
				if (in != null) Files.copy(in, charactersDir().resolve(ex[1]));
			}
		}
	}

	private static List<Path> jsonFiles(Path dir) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(out::add);
		} catch (IOException e) {
			PROBLEMS.add(dir.getFileName() + ": " + e.getMessage());
		}
		return out;
	}

	private static JsonObject readObject(InputStream in) throws IOException {
		try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		}
	}
}
