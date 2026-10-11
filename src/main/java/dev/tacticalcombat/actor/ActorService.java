package dev.tacticalcombat.actor;

import com.google.gson.JsonObject;
import dev.tacticalcombat.character.CharacterStore;
import dev.tacticalcombat.character.CharacterSync;
import dev.tacticalcombat.character.Roles;
import dev.tacticalcombat.combat.Combat;
import dev.tacticalcombat.combat.CombatManager;
import dev.tacticalcombat.combat.DmTools;
import dev.tacticalcombat.net.ActorActionPayload;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything a Dungeon Master does with Actors, shared by the Actors window and the /actor commands. Every method
 * returns null when it worked, or a short sentence saying why it did not.
 */
public final class ActorService {
	private ActorService() {}

	// ---------------------------------------------------------------- network entry

	public static void handle(ServerPlayerEntity dm, ActorActionPayload p) {
		if (!Roles.isDm(dm.getUuid()) || dm.getServer() == null) return;
		String error = switch (p.op()) {
			case ActorActionPayload.CREATE -> {
				String made = create(dm, p.a(), p.b(), (p.n() & 1) != 0, kindOf(p.c()), valueOf(p.c()), (p.n() >> 1) & 3);
				if (made == null) fileAs(p.a().trim(), p.id(), p.d());
				yield made;
			}
			case ActorActionPayload.DELETE -> delete(dm.getServer(), p.id());
			case ActorActionPayload.DUPLICATE -> duplicate(dm, p.id());
			case ActorActionPayload.SPAWN -> spawn(dm, p.id());
			case ActorActionPayload.RECALL -> recall(dm.getServer(), p.id());
			case ActorActionPayload.MODEL -> setModel(dm.getServer(), p.id(), kindOf(p.c()), valueOf(p.c()));
			case ActorActionPayload.SHEET -> setSheet(dm, p.id(), p.b(), p.n() != 0);
			case ActorActionPayload.DISPOSITION -> setDisposition(dm.getServer(), p.id(), p.n());
			case ActorActionPayload.CONTROL -> setControl(dm.getServer(), p.id(), p.n() != 0);
			case ActorActionPayload.RENAME -> rename(dm.getServer(), p.id(), p.a());
			case ActorActionPayload.FIGHT -> addToFight(dm.getServer(), p.id());
			case ActorActionPayload.POSSESS -> Possession.start(dm, p.id());
			case ActorActionPayload.RELEASE -> {
				Possession.release(dm, true);
				yield null;
			}
			case ActorActionPayload.TAKEOVER -> takeOver(dm, p.id());
			case ActorActionPayload.MOVE -> move(dm, p.id(), p.c());
			case ActorActionPayload.FOLDER -> setFolder(p.id(), p.a());
			case ActorActionPayload.TAGS -> setTags(p.id(), p.a());
			case ActorActionPayload.FOLDER_NEW -> ActorRegistry.ensureFolder(p.a()) == null ? "A folder name is 1 to 32 characters." : null;
			case ActorActionPayload.FOLDER_RENAME -> ActorRegistry.renameFolder(p.a(), p.b()) ? null : "Could not rename the folder (the name is empty or taken).";
			case ActorActionPayload.FOLDER_DELETE -> {
				ActorRegistry.deleteFolder(p.a());
				yield null;
			}
			case ActorActionPayload.IMPORT -> importSheet(dm, p.b(), p.id(), p.d(), kindOf(p.c()), valueOf(p.c()), (p.n() & 1) != 0, (p.n() >> 1) & 3);
			default -> "Unknown Actor action.";
		};
		if (error != null) dm.sendMessage(Text.literal(error), false);
		DmTools.broadcast(dm.getServer());
	}

	private static String kindOf(String model) {
		int i = model.indexOf(':');
		return (i < 0 ? model : model.substring(0, i)).toLowerCase(Locale.ROOT);
	}

	private static String valueOf(String model) {
		int i = model.indexOf(':');
		return i < 0 ? "" : model.substring(i + 1).trim();
	}

	// ---------------------------------------------------------------- records

	public static String create(ServerPlayerEntity dm, String name, String sheetId, boolean linked, String kind, String value, int disposition) {
		return create(dm, name, sheetId, linked, kind, value, disposition, true);
	}

	private static String create(ServerPlayerEntity dm, String name, String sheetId, boolean linked, String kind, String value, int disposition, boolean announce) {
		name = name == null ? "" : name.trim();
		if (name.isEmpty() || name.length() > 40) return "Give the Actor a name (up to 40 characters).";
		if (ActorRegistry.nameTaken(name)) return "There is already an Actor called " + name + ".";
		CharacterStore.Entry template = CharacterStore.get(sheetId);
		if (template == null) return "Pick a sheet for the Actor first (a character the server knows).";
		String bad = checkModel(kind, value);
		if (bad != null) return bad;

		ActorRecord r = new ActorRecord(UUID.randomUUID().toString().substring(0, 8), name);
		r.kind = kind;
		r.value = normalise(kind, value);
		r.disposition = Math.max(0, Math.min(2, disposition));
		attachSheet(dm.getServer(), dm, r, template, linked);
		ActorRegistry.put(r);
		if (announce) dm.sendMessage(Text.literal("Created Actor " + name + ". Place it with Spawn."), false);
		return null;
	}

	/**
	 * Import: makes an Actor out of an existing sheet, named after it (a free name when taken), filed in a folder and
	 * tagged. The Actors window calls this once per picked sheet.
	 */
	public static String importSheet(ServerPlayerEntity dm, String sheetId, String folder, String tags, String kind, String value, boolean linked, int disposition) {
		CharacterStore.Entry template = CharacterStore.get(sheetId);
		if (template == null) return "No such sheet.";
		String name = ActorRegistry.nameOfSheet(template).trim();
		if (name.isEmpty()) name = "Actor";
		if (name.length() > 36) name = name.substring(0, 36).trim();
		name = ActorRegistry.freeName(name);
		if (checkModel(kind, value) != null) {
			kind = "mob";
			value = "minecraft:villager";
		}
		String err = create(dm, name, sheetId, linked, kind, value, disposition, false);
		if (err != null) return err;
		fileAs(name, folder, tags);
		return null;
	}

	/** Puts the just-made Actor in its folder and gives it its tags. */
	private static void fileAs(String name, String folder, String tags) {
		ActorRecord r = ActorRegistry.find(name);
		if (r == null) return;
		String f = folder == null || folder.isBlank() ? null : ActorRegistry.ensureFolder(folder);
		r.folder = f == null ? "" : f;
		r.tags.clear();
		r.tags.addAll(ActorRegistry.parseTags(tags));
		ActorRegistry.save();
	}

	public static String setFolder(String id, String folder) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		if (folder == null || folder.isBlank()) {
			r.folder = "";
		} else {
			String f = ActorRegistry.ensureFolder(folder);
			if (f == null) return "A folder name is 1 to 32 characters.";
			r.folder = f;
		}
		ActorRegistry.save();
		return null;
	}

	public static String setTags(String id, String csv) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		r.tags.clear();
		r.tags.addAll(ActorRegistry.parseTags(csv));
		ActorRegistry.save();
		return null;
	}

	/** Points the record at {@code template} itself (linked) or at a fresh private copy of it. */
	private static void attachSheet(MinecraftServer server, ServerPlayerEntity dm, ActorRecord r, CharacterStore.Entry template, boolean linked) {
		dropOwnedSheet(server, r);
		if (linked) {
			r.sheetId = template.id;
			r.ownsSheet = false;
			return;
		}
		JsonObject copy = template.json.deepCopy();
		String id = UUID.randomUUID().toString();
		copy.addProperty("id", id);
		copy.remove("version");
		copy.remove("link");
		JsonObject text = copy.has("text") && copy.get("text").isJsonObject() ? copy.getAsJsonObject("text") : new JsonObject();
		text.addProperty("name", r.name);
		copy.add("text", text);
		CharacterStore.Entry e = new CharacterStore.Entry(id, new UUID(0L, 0L), "Actors", 1, copy); // held by the server, not by a player
		CharacterStore.put(e);
		CharacterStore.save();
		CharacterSync.broadcast(server, e, "");
		r.sheetId = id;
		r.ownsSheet = true;
	}

	private static void dropOwnedSheet(MinecraftServer server, ActorRecord r) {
		if (r.ownsSheet && !r.sheetId.isEmpty() && CharacterStore.get(r.sheetId) != null) {
			CharacterSync.removeByServer(server, r.sheetId);
		}
		r.sheetId = "";
		r.ownsSheet = false;
	}

	public static String delete(MinecraftServer server, String id) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		String err = recall(server, r.id);
		if (err != null) return err;
		dropOwnedSheet(server, r);
		ActorRegistry.remove(r.id);
		return null;
	}

	public static String duplicate(ServerPlayerEntity dm, String id) {
		ActorRecord src = ActorRegistry.find(id);
		if (src == null) return "No such Actor.";
		CharacterStore.Entry sheet = ActorRegistry.sheetOf(src);
		if (sheet == null) return src.name + " has no sheet to copy.";
		ActorRecord r = new ActorRecord(UUID.randomUUID().toString().substring(0, 8), ActorRegistry.freeName(src.name));
		r.kind = src.kind;
		r.value = src.value;
		r.disposition = src.disposition;
		r.dmControl = src.dmControl;
		r.folder = src.folder;
		r.tags.addAll(src.tags);
		attachSheet(dm.getServer(), dm, r, sheet, !src.ownsSheet); // a copy of a private sheet gets its own, a shared one stays shared
		ActorRegistry.put(r);
		return null;
	}

	public static String rename(MinecraftServer server, String id, String name) {
		ActorRecord r = ActorRegistry.find(id);
		name = name == null ? "" : name.trim();
		if (r == null) return "No such Actor.";
		if (name.isEmpty() || name.length() > 40) return "Give the Actor a name (up to 40 characters).";
		if (!name.equalsIgnoreCase(r.name) && ActorRegistry.nameTaken(name)) return "There is already an Actor called " + name + ".";
		r.name = name;
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		if (body != null) body.setCustomName(Text.literal(name));
		CharacterStore.Entry sheet = ActorRegistry.sheetOf(r);
		if (r.ownsSheet && sheet != null) {
			JsonObject text = sheet.json.has("text") && sheet.json.get("text").isJsonObject() ? sheet.json.getAsJsonObject("text") : new JsonObject();
			text.addProperty("name", name);
			sheet.json.add("text", text);
			CharacterSync.changedByServer(server, sheet);
		}
		ActorRegistry.save();
		return null;
	}

	public static String setSheet(ServerPlayerEntity dm, String id, String sheetId, boolean linked) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		CharacterStore.Entry template = CharacterStore.get(sheetId);
		if (template == null) return "No such sheet.";
		if (dev.tacticalcombat.combat.CombatManager.isInCombat(ActorRegistry.bodyOf(dm.getServer(), r))) return r.name + " is in a fight.";
		attachSheet(dm.getServer(), dm, r, template, linked);
		ActorRegistry.save();
		return null;
	}

	public static String setDisposition(MinecraftServer server, String id, int disposition) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		r.disposition = Math.max(0, Math.min(2, disposition));
		ActorRegistry.save();
		return null;
	}

	public static String setControl(MinecraftServer server, String id, boolean dmControl) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		r.dmControl = dmControl;
		ActorRegistry.save();
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		Combat c = body == null ? null : CombatManager.get(body);
		if (c != null) {
			c.markGridDirty(); // the squares of its turn appear or go
			c.sync();
		}
		return null;
	}

	// ---------------------------------------------------------------- models

	public static String checkModel(String kind, String value) {
		value = value == null ? "" : value.trim();
		switch (kind) {
			case "mob" -> {
				Identifier id = Identifier.tryParse(value.contains(":") ? value : "minecraft:" + value);
				if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return "Unknown creature '" + value + "'. Try minecraft:wolf.";
				EntityType<?> t = Registries.ENTITY_TYPE.get(id);
				if (t == ModEntities.ACTOR) return "Pick a creature, not the Actor body itself.";
				if (t == EntityType.ENDER_DRAGON || t == EntityType.WITHER) return "That creature is too big to be an Actor.";
			}
			case "item" -> {
				Identifier id = Identifier.tryParse(value.contains(":") ? value : "minecraft:" + value);
				if (id == null || !Registries.ITEM.containsId(id)) return "Unknown item '" + value + "'. Try minecraft:diamond_sword.";
			}
			case "block" -> {
				Identifier id = Identifier.tryParse(value.contains(":") ? value : "minecraft:" + value);
				if (id == null || !Registries.BLOCK.containsId(id)) return "Unknown block '" + value + "'. Try minecraft:chest.";
			}
			case "skin" -> {
				if (value.isEmpty() || value.length() > 100) return "Give a player name (whose skin to wear) or a texture id.";
			}
			default -> {
				return "A model is a mob, a skin, an item or a block.";
			}
		}
		return null;
	}

	/** Mobs, items and blocks are stored with their namespace spelled out. */
	private static String normalise(String kind, String value) {
		value = value.trim();
		if (!kind.equals("skin") && !value.contains(":")) return "minecraft:" + value;
		return value;
	}

	public static String setModel(MinecraftServer server, String id, String kind, String value) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		String bad = checkModel(kind, value);
		if (bad != null) return bad;
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		if (body != null && CombatManager.isInCombat(body)) return r.name + " is in a fight; change the model afterwards.";
		r.kind = kind;
		r.value = normalise(kind, value);
		ActorRegistry.save();
		if (body != null && body.getWorld() instanceof ServerWorld w) {
			Vec3d at = body.getPos();
			float yaw = body.getYaw();
			body.discard();
			r.entityUuid = null;
			String err = place(w, r, at, yaw);
			if (err != null) return err;
		}
		return null;
	}

	// ---------------------------------------------------------------- bodies

	/** Places the Actor where the Dungeon Master looks (or at their feet). */
	public static String spawn(ServerPlayerEntity dm, String id) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		if (!(dm.getWorld() instanceof ServerWorld w)) return "Not in a world.";
		LivingEntity existing = ActorRegistry.bodyOf(dm.getServer(), r);
		if (existing != null) {
			if (CombatManager.isInCombat(existing)) return r.name + " is in a fight.";
			existing.discard(); // placing again moves it
			r.entityUuid = null;
		}
		Vec3d at = dm.getPos();
		HitResult hit = dm.raycast(64.0, 1.0f, false);
		if (hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
			at = Vec3d.ofBottomCenter(b.getBlockPos().offset(b.getSide()));
		}
		return place(w, r, at, dm.getYaw() + 180f);
	}

	public static String recall(MinecraftServer server, String id) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		if (body != null) {
			if (CombatManager.isInCombat(body)) return r.name + " is in a fight.";
			body.discard();
		}
		r.entityUuid = null;
		ActorRegistry.save();
		return null;
	}

	/** Builds the body for the record's model and puts it into the world. */
	private static String place(ServerWorld world, ActorRecord r, Vec3d at, float yaw) {
		MobEntity body;
		if (r.kind.equals("mob")) {
			Identifier id = Identifier.tryParse(r.value);
			Entity e = id == null ? null : Registries.ENTITY_TYPE.get(id).create(world);
			if (!(e instanceof MobEntity m) || e instanceof EnderDragonEntity || e instanceof WitherEntity) {
				return r.value + " can not be an Actor body (it has to be a mob).";
			}
			body = m;
		} else {
			ActorEntity a = ModEntities.ACTOR.create(world);
			if (a == null) return "Could not create the Actor body.";
			a.setModel(r.model());
			body = a;
		}
		body.setAiDisabled(true);
		body.setPersistent();
		body.setInvulnerable(true);
		body.setSilent(true);
		body.setCanPickUpLoot(false);
		body.setCustomName(Text.literal(r.name));
		body.setCustomNameVisible(true);
		body.addCommandTag(ActorRegistry.TAG + r.id);
		body.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
		body.setHeadYaw(yaw);
		body.setBodyYaw(yaw);
		world.spawnEntity(body);
		r.entityUuid = body.getUuid();
		ActorRegistry.save();
		return null;
	}

	/** Moves a placed Actor to where the Dungeon Master pointed (the Stage). Inside a fight the grid moves it instead. */
	public static String move(ServerPlayerEntity dm, String id, String where) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		LivingEntity body = ActorRegistry.bodyOf(dm.getServer(), r);
		if (body == null) return r.name + " is not placed in the world.";
		if (CombatManager.isInCombat(body)) return r.name + " is in a fight: it moves on the grid.";
		String[] parts = where == null ? new String[0] : where.trim().split("\\s+");
		if (parts.length != 3) return "Bad position.";
		double x, y, z;
		try {
			x = Double.parseDouble(parts[0]);
			y = Double.parseDouble(parts[1]);
			z = Double.parseDouble(parts[2]);
		} catch (NumberFormatException ex) {
			return "Bad position.";
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return "Bad position.";
		if (dm.squaredDistanceTo(x, y, z) > 160.0 * 160.0) return "Too far away.";
		if (body.getWorld() != dm.getWorld()) return r.name + " is in another dimension.";
		if (body instanceof MobEntity m) m.getNavigation().stop();
		body.setVelocity(Vec3d.ZERO);
		body.refreshPositionAndAngles(x, y, z, body.getYaw(), body.getPitch());
		return null;
	}

	/** Cancels an Actor's automatic turn and hands it to the Dungeon Master. */
	public static String takeOver(ServerPlayerEntity dm, String id) {
		Combat c;
		if (id == null || id.isEmpty()) {
			c = CombatManager.primary();
			if (c == null) return "No fight is running.";
		} else {
			ActorRecord r = ActorRegistry.find(id);
			LivingEntity body = r == null ? null : ActorRegistry.bodyOf(dm.getServer(), r);
			if (body == null) return "That Actor is not placed in the world.";
			c = CombatManager.get(body);
			if (c == null) return r.name + " is not in a fight.";
			if (!c.isTurnOf(body)) return "It is not " + r.name + "'s turn.";
		}
		return c.takeOver();
	}

	public static String addToFight(MinecraftServer server, String id) {
		ActorRecord r = ActorRegistry.find(id);
		if (r == null) return "No such Actor.";
		LivingEntity body = ActorRegistry.bodyOf(server, r);
		if (body == null) return r.name + " is not placed in the world.";
		Combat c = CombatManager.primary();
		if (c == null) return "No fight is running. Start an encounter first (hostile Actors nearby join by themselves).";
		if (c.contains(body)) return r.name + " is already in the fight.";
		c.add(body);
		return null;
	}

	/** Actors the Dungeon Master can see in a list: the records, in creation order. */
	public static List<ActorRecord> list() {
		return ActorRegistry.all();
	}
}
