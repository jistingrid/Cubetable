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
 * Finds game packs, sheet formats, themes and characters.
 *
 * <p>A <b>game pack</b> is a folder: {@code format.json} (the sheet format) and optionally {@code themes/*.json}.
 * The mod itself only ships the colour themes ({@code core}, listed in {@code assets/tacticalcombat/sheets/packs.json}).
 * Games are packs in {@code config/tacticalcombat/packs/<name>/} (the repository's {@code packs/} folder has examples),
 * which may carry example {@code characters/} that are copied once into an empty characters folder. A pack with the
 * same format id as an earlier one replaces it. Loose {@code sheets/*.json} and {@code themes/*.json} files in the config folder
 * still work. Characters are always {@code config/tacticalcombat/characters/*.json}.
 *
 * <p>Client side only; nothing here touches the game yet.
 */
public final class SheetLibrary {
	private static final String RES = "/assets/tacticalcombat/sheets/";

	public static final Map<String, SheetFormat> FORMATS = new LinkedHashMap<>();
	public static final Map<String, Theme> THEMES = new LinkedHashMap<>();
	public static final List<CharacterData> CHARACTERS = new ArrayList<>();
	/** Names of the packs that were loaded, in load order (shown in the Formats view). */
	public static final List<String> PACKS = new ArrayList<>();
	/** Files that could not be read, with the reason; shown in the window so a typo is easy to find. */
	public static final List<String> PROBLEMS = new ArrayList<>();

	private SheetLibrary() {}

	private static Path root() {
		return FabricLoader.getInstance().getConfigDir().resolve("tacticalcombat");
	}

	/** Folder for game packs (one sub-folder per game). */
	public static Path packsDir() {
		return root().resolve("packs");
	}

	/** Loose format files (older layout, still read). */
	public static Path sheetsDir() {
		return root().resolve("sheets");
	}

	public static Path themesDir() {
		return root().resolve("themes");
	}

	public static Path charactersDir() {
		return root().resolve("characters");
	}

	public static Theme theme(String id) {
		Theme t = id == null ? null : THEMES.get(id.toLowerCase(java.util.Locale.ROOT));
		if (t == null) t = THEMES.get(Theme.DEFAULT_ID);
		return t != null ? t : Theme.fallback();
	}

	public static void reload() {
		FORMATS.clear();
		THEMES.clear();
		CHARACTERS.clear();
		PACKS.clear();
		PROBLEMS.clear();

		// 1. built-in packs
		List<String> builtIn = new ArrayList<>();
		try (InputStream in = SheetLibrary.class.getResourceAsStream(RES + "packs.json")) {
			if (in != null) {
				try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
					JsonParser.parseReader(r).getAsJsonArray().forEach(e -> builtIn.add(e.getAsString()));
				}
			}
		} catch (Exception e) {
			PROBLEMS.add("packs.json: " + e.getMessage());
		}
		for (String pack : builtIn) {
			loadBuiltInPack(pack);
		}

		try {
			Files.createDirectories(packsDir());
			Files.createDirectories(sheetsDir());
			Files.createDirectories(themesDir());
			Files.createDirectories(charactersDir());
			seedExamples(builtIn);
		} catch (IOException e) {
			PROBLEMS.add("config folder: " + e.getMessage());
		}

		// 2. your packs (a folder each), then loose files
		for (Path dir : subDirs(packsDir())) {
			loadFolderPack(dir);
		}
		for (Path p : jsonFiles(sheetsDir())) {
			readFormat(p, p.getFileName().toString());
		}
		for (Path p : jsonFiles(themesDir())) {
			readTheme(p);
		}
		for (Path p : jsonFiles(charactersDir())) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				CHARACTERS.add(CharacterData.parse(JsonParser.parseReader(r).getAsJsonObject(), p.getFileName().toString()));
			} catch (Exception e) {
				PROBLEMS.add(p.getFileName() + ": " + e.getMessage());
			}
		}
		TacticalCombatMod.LOGGER.info("Sheets: {} packs, {} formats, {} themes, {} characters, {} problems",
				PACKS.size(), FORMATS.size(), THEMES.size(), CHARACTERS.size(), PROBLEMS.size());
	}

	private static void loadBuiltInPack(String pack) {
		try (InputStream in = SheetLibrary.class.getResourceAsStream(RES + pack + "/pack.json")) {
			if (in == null) {
				PROBLEMS.add("built-in pack " + pack + ": pack.json missing");
				return;
			}
			JsonObject manifest = readObject(in);
			PACKS.add(manifest.has("name") ? manifest.get("name").getAsString() : pack);
			if (manifest.has("format")) {
				try (InputStream f = SheetLibrary.class.getResourceAsStream(RES + pack + "/" + manifest.get("format").getAsString())) {
					SheetFormat format = SheetFormat.parse(readObject(f), "built in");
					FORMATS.put(format.id, format);
				}
			}
			if (manifest.has("themes")) {
				for (var e : manifest.getAsJsonArray("themes")) {
					try (InputStream t = SheetLibrary.class.getResourceAsStream(RES + pack + "/" + e.getAsString())) {
						Theme theme = Theme.parse(readObject(t), e.getAsString());
						THEMES.put(theme.id, theme);
					}
				}
			}
		} catch (Exception e) {
			PROBLEMS.add("built-in pack " + pack + ": " + e.getMessage());
		}
	}

	private static void loadFolderPack(Path dir) {
		String name = dir.getFileName().toString();
		boolean any = false;
		Path format = dir.resolve("format.json");
		if (Files.isRegularFile(format)) {
			readFormat(format, "pack " + name);
			any = true;
		}
		for (Path p : jsonFiles(dir.resolve("themes"))) {
			readTheme(p);
			any = true;
		}
		if (any) PACKS.add(name);
		else PROBLEMS.add("pack " + name + ": needs a format.json (and/or themes/)");
	}

	private static void readFormat(Path p, String source) {
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			SheetFormat f = SheetFormat.parse(JsonParser.parseReader(r).getAsJsonObject(), source);
			FORMATS.put(f.id, f);
		} catch (Exception e) {
			PROBLEMS.add(source + ": " + e.getMessage());
		}
	}

	private static void readTheme(Path p) {
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			Theme t = Theme.parse(JsonParser.parseReader(r).getAsJsonObject(), p.getFileName().toString().replace(".json", ""));
			THEMES.put(t.id, t);
		} catch (Exception e) {
			PROBLEMS.add(p.getFileName() + ": " + e.getMessage());
		}
	}

	/** Writes a character to its file (a new character gets a file name from its name) and keeps the list current. */
	public static void save(CharacterData c) throws IOException {
		Files.createDirectories(charactersDir());
		if (c.file.isEmpty()) {
			String slug = c.displayName().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
			if (slug.isEmpty()) slug = "character";
			String name = slug + ".json";
			for (int n = 2; Files.exists(charactersDir().resolve(name)); n++) {
				name = slug + "_" + n + ".json";
			}
			c.file = name;
		}
		String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(c.toJson());
		Files.writeString(charactersDir().resolve(c.file), json, StandardCharsets.UTF_8);
		for (int i = 0; i < CHARACTERS.size(); i++) {
			if (CHARACTERS.get(i).file.equals(c.file)) {
				CHARACTERS.set(i, c);
				return;
			}
		}
		CHARACTERS.add(c);
	}

	public static void delete(CharacterData c) throws IOException {
		Files.deleteIfExists(charactersDir().resolve(c.file));
		CHARACTERS.removeIf(o -> o.file.equals(c.file));
	}

	/** First run: drop the example characters of the built-in packs into the (empty) characters folder. */
	private static void seedExamples(List<String> builtIn) throws IOException {
		if (!jsonFiles(charactersDir()).isEmpty()) return;
		for (String pack : builtIn) {
			try (InputStream in = SheetLibrary.class.getResourceAsStream(RES + pack + "/pack.json")) {
				if (in == null) continue;
				JsonObject manifest = readObject(in);
				if (!manifest.has("characters")) continue;
				for (var e : manifest.getAsJsonArray("characters")) {
					String path = e.getAsString();
					try (InputStream c = SheetLibrary.class.getResourceAsStream(RES + pack + "/" + path)) {
						if (c != null) Files.copy(c, charactersDir().resolve(path.substring(path.lastIndexOf('/') + 1)));
					}
				}
			}
		}
		// example characters shipped inside game packs (packs/<game>/characters/*.json)
		for (Path dir : subDirs(packsDir())) {
			for (Path p : jsonFiles(dir.resolve("characters"))) {
				Path target = charactersDir().resolve(p.getFileName().toString());
				if (!Files.exists(target)) Files.copy(p, target);
			}
		}
	}

	private static List<Path> subDirs(Path dir) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(Files::isDirectory).sorted().forEach(out::add);
		} catch (IOException e) {
			PROBLEMS.add(dir.getFileName() + ": " + e.getMessage());
		}
		return out;
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
