package dev.tacticalcombat.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tacticalcombat.sheet.CharacterData;
import dev.tacticalcombat.sheet.CodexRules;
import dev.tacticalcombat.sheet.SheetContext;
import dev.tacticalcombat.sheet.SheetFormat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads the codex of every game pack: {@code packs/<game>/codex/<type>/**.json}. A file holds one entry (an ordinary
 * character file, with an optional {@code "codex"} note for the codex's own use), a list of entries, or a bundle
 * {@code {"entries": [...]}}. Sub-folders become folders in the codex window. Nothing here knows any game: names, kinds
 * and the figures shown come from the pack's format (see {@link CodexRules}). Free of Minecraft code, so it can be tested
 * on its own.
 *
 * <pre>
 * "codex": {                          optional, inside an entry
 *   "model": "mob:minecraft:wolf",    what the Actor looks like (else guessed from the name)
 *   "side": "hostile",                hostile / neutral / friendly
 *   "tags": ["beast", "pack"],
 *   "folder": "beasts"                overrides the sub-folder the file sits in
 * }
 * </pre>
 */
public final class CodexLibrary {
	private static final int MAX_DEPTH = 6;
	private static final int MAX_TAGS = 8;

	/** What a scan found: the entries, and a sentence for everything that could not be used. */
	public record Result(List<CodexEntry> entries, List<String> problems) {}

	/** What dropping files onto the codex did. */
	public record Imported(int files, int entries, List<String> errors) {}

	private CodexLibrary() {}

	// ------------------------------------------------------------------ scanning

	public static Result scan(Path packsDir, Map<String, SheetFormat> formats) {
		Scan s = new Scan(formats);
		for (Path pack : subDirs(packsDir, s.problems)) {
			Path codexDir = pack.resolve("codex");
			if (!Files.isDirectory(codexDir)) continue;
			String packName = pack.getFileName().toString();
			String packFormat = formatIdOf(pack);
			for (String type : CodexRules.TYPES) {
				Path typeDir = codexDir.resolve(type);
				if (!Files.isDirectory(typeDir)) continue;
				for (Path file : jsonFiles(typeDir, s.problems)) s.readFile(packName, packFormat, type, typeDir, file);
			}
		}
		return new Result(s.entries, s.problems);
	}

	private static final class Scan {
		final Map<String, SheetFormat> formats;
		final List<CodexEntry> entries = new ArrayList<>();
		final List<String> problems = new ArrayList<>();
		final Set<String> keys = new HashSet<>();
		/** Problems already reported once (a missing pack, a broken column formula) so a long bestiary does not repeat them. */
		final Set<String> told = new HashSet<>();

		Scan(Map<String, SheetFormat> formats) {
			this.formats = formats;
		}

		void problem(String text) {
			problems.add(text);
		}

		void problemOnce(String key, String text) {
			if (told.add(key)) problems.add(text);
		}

		void readFile(String pack, String packFormat, String type, Path typeDir, Path file) {
			Path relative = typeDir.relativize(file);
			String where = pack + "/codex/" + type + "/" + relative.toString().replace('\\', '/');
			JsonElement root;
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				root = JsonParser.parseReader(r);
			} catch (IOException | RuntimeException e) {
				problem(where + ": " + reason(e));
				return;
			}
			String folder = "";
			Path parent = relative.getParent();
			if (parent != null) {
				List<String> parts = new ArrayList<>();
				for (Path p : parent) parts.add(p.toString());
				folder = String.join("/", parts);
			}
			String bundleFormat = null;
			String bundleFolder = null;
			List<JsonObject> objects = new ArrayList<>();
			boolean single = false;
			if (root.isJsonArray()) {
				for (JsonElement e : root.getAsJsonArray()) {
					if (e.isJsonObject()) objects.add(e.getAsJsonObject());
					else problem(where + ": a list entry is not an object");
				}
			} else if (root.isJsonObject() && isBundle(root.getAsJsonObject())) {
				JsonObject b = root.getAsJsonObject();
				bundleFormat = text(b, "format");
				bundleFolder = text(b, "folder");
				for (JsonElement e : b.getAsJsonArray("entries")) {
					if (e.isJsonObject()) objects.add(e.getAsJsonObject());
					else problem(where + ": a list entry is not an object");
				}
			} else if (root.isJsonObject()) {
				objects.add(root.getAsJsonObject());
				single = true;
			} else {
				problem(where + ": expected an entry, a list of entries or {\"entries\": [...]}");
				return;
			}
			String stem = file.getFileName().toString().replaceAll("(?i)\\.json$", "");
			for (JsonObject o : objects) {
				load(o, pack, packFormat != null ? packFormat : "", type, folder, where, single ? prettify(stem) : "", bundleFormat, bundleFolder);
			}
		}

		void load(JsonObject raw, String pack, String packFormat, String type, String folder, String where, String fallbackName,
				String bundleFormat, String bundleFolder) {
			JsonObject o = raw.deepCopy();
			JsonObject meta = o.has("codex") && o.get("codex").isJsonObject() ? o.getAsJsonObject("codex") : new JsonObject();
			o.remove("codex");
			for (String k : new String[] {"link", "linkVersion", "id", "version"}) o.remove(k);

			String formatId = firstNonBlank(text(o, "format"), bundleFormat, packFormat);
			if (formatId.isEmpty()) {
				problem(where + ": no \"format\" (and the pack has no readable format.json to take it from)");
				return;
			}
			SheetFormat f = formats.get(formatId);
			if (f == null) {
				problemOnce("format:" + formatId + ":" + where, where + ": needs the game pack '" + formatId + "', which is not installed");
				return;
			}
			o.addProperty("format", formatId);

			JsonObject text = o.has("text") && o.get("text").isJsonObject() ? o.getAsJsonObject("text") : new JsonObject();
			String name = firstNonBlank(text(text, "name"), fallbackName);
			if (name.isEmpty()) {
				problem(where + ": an entry has no name (\"text\": {\"name\": ...})");
				return;
			}
			text.addProperty("name", name);
			o.add("text", text);

			if (!o.has("kind")) {
				o.addProperty("kind", f.codexKind(type));
			} else {
				String kind = text(o, "kind").toLowerCase(Locale.ROOT);
				if (!f.layouts.containsKey(kind)) {
					problem(where + ": " + name + " uses the sheet kind '" + kind + "', which " + f.name + " does not have");
					return;
				}
				o.addProperty("kind", kind);
			}

			CharacterData data;
			try {
				data = CharacterData.parse(o.deepCopy(), where);
			} catch (RuntimeException e) {
				problem(where + ": " + name + ": " + reason(e));
				return;
			}
			String key = CodexKeys.key(formatId, type, name);
			if (!keys.add(key)) {
				problem(where + ": there is already an entry called '" + name + "' in " + f.name + "; this one is ignored");
				return;
			}

			String model = text(meta, "model");
			if (!model.isEmpty() && !model.matches("(mob|skin|item|block):.+")) {
				problem(where + ": " + name + ": codex.model must look like mob:minecraft:wolf (mob, skin, item or block); ignored");
				model = "";
			}
			int side = sideOf(meta.get("side"));
			String entryFolder = cleanFolder(firstNonBlank(text(meta, "folder"), bundleFolder, folder));
			List<String> tags = tagsOf(meta.get("tags"));

			out(new CodexEntry(pack, formatId, type, entryFolder, where, name, text(text, "subtitle"), o, model, side, tags,
					stats(f, type, data, where), preview(f, data)));
		}

		void out(CodexEntry e) {
			entries.add(e);
		}

		List<CodexEntry.Stat> stats(SheetFormat f, String type, CharacterData data, String where) {
			List<CodexEntry.Stat> stats = new ArrayList<>();
			SheetContext ctx = new SheetContext(f, data);
			for (CodexRules.Column col : f.codexType(type).columns) {
				String shown;
				if (col.text() != null) {
					shown = data.texts.getOrDefault(col.text(), "");
				} else {
					String error = ctx.error(col.value());
					if (error != null) {
						shown = "?";
						problemOnce("column:" + f.id + ":" + col.label(), f.name + " codex column '" + col.label() + "': " + error + " (first seen in " + where + ")");
					} else {
						shown = ctx.show(col.value(), col.signed());
					}
				}
				stats.add(new CodexEntry.Stat(col.label(), shown));
			}
			return stats;
		}
	}

	/** "Collection label: name, name" for every collection of the sheet that has rows. */
	private static List<CodexEntry.Line> preview(SheetFormat f, CharacterData data) {
		List<CodexEntry.Line> lines = new ArrayList<>();
		for (SheetFormat.Collection c : f.collections.values()) {
			List<CharacterData.Row> rows = data.collections.get(c.id);
			if (rows == null || rows.isEmpty()) continue;
			String nameColumn = c.nameColumn;
			if (nameColumn.isEmpty()) {
				for (SheetFormat.Col col : c.columns) {
					if (col.type.equals("text")) {
						nameColumn = col.id;
						break;
					}
				}
			}
			List<String> names = new ArrayList<>();
			for (CharacterData.Row r : rows) {
				String n = nameColumn.isEmpty() ? "" : r.texts.getOrDefault(nameColumn, "").trim();
				if (!n.isEmpty()) names.add(n);
			}
			if (!names.isEmpty()) lines.add(new CodexEntry.Line(c.label, String.join(", ", names)));
		}
		return lines;
	}

	// ------------------------------------------------------------------ importing files

	/**
	 * Copies .json files (or the .json files of dropped folders) into a pack's codex folder. The pack is the one whose
	 * format an entry names, else {@code fallbackPack} (the game picked in the window). Nothing is installed from a file
	 * that is not valid JSON with at least one entry; entries that the game cannot use are reported by the next scan.
	 */
	public static Imported importFiles(Path packsDir, List<Path> sources, String fallbackPack, String type) {
		int files = 0;
		int entries = 0;
		List<String> errors = new ArrayList<>();
		List<Path> all = new ArrayList<>();
		for (Path src : sources) {
			if (Files.isDirectory(src)) {
				try (Stream<Path> walk = Files.walk(src, MAX_DEPTH)) {
					walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
							.sorted().forEach(all::add);
				} catch (IOException e) {
					errors.add(src.getFileName() + ": " + e.getMessage());
				}
			} else {
				all.add(src);
			}
		}
		for (Path src : all) {
			String name = src.getFileName().toString();
			if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) {
				errors.add(name + ": only .json files can be added to the codex");
				continue;
			}
			JsonElement root;
			try (Reader r = Files.newBufferedReader(src, StandardCharsets.UTF_8)) {
				root = JsonParser.parseReader(r);
			} catch (IOException | RuntimeException e) {
				errors.add(name + ": " + reason(e));
				continue;
			}
			List<JsonObject> objects = entryObjects(root);
			if (objects.isEmpty()) {
				errors.add(name + ": no entries found in it");
				continue;
			}
			String formatId = "";
			for (JsonObject o : objects) {
				formatId = firstNonBlank(text(o, "format"), root.isJsonObject() ? text(root.getAsJsonObject(), "format") : "");
				if (!formatId.isEmpty()) break;
			}
			String pack = formatId.isEmpty() ? "" : packFolderFor(packsDir, formatId);
			if (pack.isEmpty()) pack = fallbackPack == null ? "" : fallbackPack;
			if (pack.isEmpty()) {
				errors.add(name + ": could not tell which game it belongs to; pick a game in the window first");
				continue;
			}
			if (!formatId.isEmpty() && packFormatMismatch(packsDir, pack, formatId)) {
				errors.add(name + ": it is for the game '" + formatId + "', which is not the game you picked; pick that game (or install its pack)");
				continue;
			}
			Path dir = packsDir.resolve(pack).resolve("codex").resolve(type);
			try {
				Files.createDirectories(dir);
				Files.copy(src, freeFile(dir, name));
				files++;
				entries += objects.size();
			} catch (IOException e) {
				errors.add(name + ": " + e.getMessage());
			}
		}
		return new Imported(files, entries, errors);
	}

	private static boolean packFormatMismatch(Path packsDir, String pack, String formatId) {
		String own = formatIdOf(packsDir.resolve(pack));
		return own != null && !own.equals(formatId);
	}

	/** The folder of the pack whose format has this id, or "". */
	public static String packFolderFor(Path packsDir, String formatId) {
		List<String> ignored = new ArrayList<>();
		for (Path dir : subDirs(packsDir, ignored)) {
			if (formatId.equals(formatIdOf(dir))) return dir.getFileName().toString();
		}
		return "";
	}

	/** The id written in a pack's format.json, or null when it has none or cannot be read. */
	public static String formatIdOf(Path packDir) {
		Path file = packDir.resolve("format.json");
		if (!Files.isRegularFile(file)) return null;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement e = JsonParser.parseReader(r);
			if (!e.isJsonObject()) return null;
			String id = text(e.getAsJsonObject(), "id");
			return id.isEmpty() ? null : id;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static Path folderOf(Path packsDir, String pack, String type) {
		return packsDir.resolve(pack).resolve("codex").resolve(type);
	}

	private static Path freeFile(Path dir, String name) {
		Path target = dir.resolve(name);
		if (!Files.exists(target)) return target;
		String stem = name.replaceAll("(?i)\\.json$", "");
		for (int n = 2; ; n++) {
			target = dir.resolve(stem + "_" + n + ".json");
			if (!Files.exists(target)) return target;
		}
	}

	// ------------------------------------------------------------------ small helpers

	private static boolean isBundle(JsonObject o) {
		return o.has("entries") && o.get("entries").isJsonArray() && !o.has("text");
	}

	/** The entry objects a parsed file holds (one, a list, or a bundle). */
	public static List<JsonObject> entryObjects(JsonElement root) {
		List<JsonObject> out = new ArrayList<>();
		if (root.isJsonArray()) {
			for (JsonElement e : root.getAsJsonArray()) if (e.isJsonObject()) out.add(e.getAsJsonObject());
		} else if (root.isJsonObject() && isBundle(root.getAsJsonObject())) {
			for (JsonElement e : root.getAsJsonObject().getAsJsonArray("entries")) if (e.isJsonObject()) out.add(e.getAsJsonObject());
		} else if (root.isJsonObject()) {
			out.add(root.getAsJsonObject());
		}
		return out;
	}

	/** One readable line for a failure: no class names, no help links, no second line. */
	private static String reason(Exception e) {
		String m = e.getMessage();
		if (m == null || m.isBlank()) return e.getClass().getSimpleName();
		m = m.replaceFirst("^[\\w.]+(Exception|Error): ", "");
		int nl = m.indexOf('\n');
		if (nl >= 0) m = m.substring(0, nl);
		return m.trim().isEmpty() ? e.getClass().getSimpleName() : m.trim();
	}

	private static String text(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) return "";
		return o.get(key).getAsString().trim();
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) if (v != null && !v.isBlank()) return v.trim();
		return "";
	}

	/** "giant_rat" -> "Giant Rat" (only used when a single-entry file has no name of its own). */
	private static String prettify(String stem) {
		StringBuilder sb = new StringBuilder();
		for (String w : stem.replaceAll("[_\\-]+", " ").trim().split("\\s+")) {
			if (w.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.toString();
	}

	private static int sideOf(JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) return -1;
		String s = e.getAsString().trim().toLowerCase(Locale.ROOT);
		return switch (s) {
			case "hostile", "0", "enemy" -> CodexEntry.HOSTILE;
			case "neutral", "1" -> CodexEntry.NEUTRAL;
			case "friendly", "2", "ally" -> CodexEntry.FRIENDLY;
			default -> -1;
		};
	}

	private static List<String> tagsOf(JsonElement e) {
		List<String> raw = new ArrayList<>();
		if (e != null && e.isJsonArray()) {
			for (JsonElement t : e.getAsJsonArray()) if (t.isJsonPrimitive()) raw.add(t.getAsString());
		} else if (e != null && e.isJsonPrimitive()) {
			for (String t : e.getAsString().split(",")) raw.add(t);
		}
		List<String> tags = new ArrayList<>();
		for (String t : raw) {
			String clean = t.trim();
			while (clean.startsWith("#")) clean = clean.substring(1).trim();
			if (clean.length() > 20) clean = clean.substring(0, 20).trim();
			boolean dup = false;
			for (String have : tags) if (have.equalsIgnoreCase(clean)) dup = true;
			if (!clean.isEmpty() && !dup && tags.size() < MAX_TAGS) tags.add(clean);
		}
		return tags;
	}

	private static String cleanFolder(String folder) {
		String f = folder.replace('\\', '/').trim();
		while (f.startsWith("/")) f = f.substring(1);
		while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
		return f.replaceAll("/{2,}", "/");
	}

	private static List<Path> subDirs(Path dir, List<String> problems) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(Files::isDirectory).sorted().forEach(out::add);
		} catch (IOException e) {
			problems.add(dir.getFileName() + ": " + e.getMessage());
		}
		return out;
	}

	private static List<Path> jsonFiles(Path dir, List<String> problems) {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> s = Files.walk(dir, MAX_DEPTH)) {
			s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
					.sorted().forEach(out::add);
		} catch (IOException e) {
			problems.add(dir.getFileName() + ": " + e.getMessage());
		}
		return out;
	}
}
