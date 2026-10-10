package dev.tacticalcombat.actor;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * The body of an Actor whose model is not a Minecraft creature: a player-shaped figure with a chosen skin, or a
 * floating item or block. It does nothing on its own (its AI is off); the model text ("skin:Name", "item:minecraft:...",
 * "block:minecraft:...") is synced to clients, whose renderer draws it.
 */
public class ActorEntity extends PathAwareEntity {
	private static final TrackedData<String> MODEL = DataTracker.registerData(ActorEntity.class, TrackedDataHandlerRegistry.STRING);

	/** Client side cache for the renderer: the item / block / skin the model text resolved to. */
	public Object cachedFor;
	public Object cached;

	public ActorEntity(EntityType<? extends ActorEntity> type, World world) {
		super(type, world);
	}

	public static DefaultAttributeContainer.Builder createActorAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(MODEL, "skin:");
	}

	public String getModel() {
		return dataTracker.get(MODEL);
	}

	public void setModel(String model) {
		dataTracker.set(MODEL, model);
	}

	/** "skin", "item" or "block". */
	public String modelKind() {
		String m = getModel();
		int i = m.indexOf(':');
		return i < 0 ? m : m.substring(0, i);
	}

	public String modelValue() {
		String m = getModel();
		int i = m.indexOf(':');
		return i < 0 ? "" : m.substring(i + 1);
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putString("ActorModel", getModel());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("ActorModel")) setModel(nbt.getString("ActorModel"));
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}
}
