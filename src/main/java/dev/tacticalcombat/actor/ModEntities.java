package dev.tacticalcombat.actor;

import dev.tacticalcombat.TacticalCombatMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
	public static EntityType<ActorEntity> ACTOR;

	private ModEntities() {}

	public static void init() {
		ACTOR = Registry.register(Registries.ENTITY_TYPE, Identifier.of(TacticalCombatMod.MOD_ID, "actor"),
				EntityType.Builder.create(ActorEntity::new, SpawnGroup.MISC)
						.dimensions(0.6f, 1.8f)
						.maxTrackingRange(10)
						.build("actor"));
		FabricDefaultAttributeRegistry.register(ACTOR, ActorEntity.createActorAttributes());
	}
}
