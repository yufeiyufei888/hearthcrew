package io.github.yufeiyufei888.hearthcrew;

import io.github.yufeiyufei888.hearthcrew.entity.LegacyCompanionEntity;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewCommands;
import io.github.yufeiyufei888.hearthcrew.runtime.WorldEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(HearthCrew.ID)
public final class HearthCrew {
    public static final String ID = "hearthcrew";
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, ID);
    public static final DeferredHolder<EntityType<?>, EntityType<LegacyCompanionEntity>> COMPANION = ENTITIES.register(
            "companion", () -> EntityType.Builder.of(LegacyCompanionEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F).eyeHeight(1.62F).clientTrackingRange(10).updateInterval(1)
                    .build(ID + ":companion"));

    public HearthCrew(IEventBus bus) {
        ENTITIES.register(bus);
        bus.addListener(this::attributes);
        bus.addListener(io.github.yufeiyufei888.hearthcrew.network.UiNetwork::register);
        NeoForge.EVENT_BUS.addListener(CrewCommands::register);
        NeoForge.EVENT_BUS.addListener(WorldEvents::entityJoined);
        NeoForge.EVENT_BUS.addListener(io.github.yufeiyufei888.hearthcrew.runtime.PickupEvents::acquired);
        NeoForge.EVENT_BUS.addListener(WorldEvents::entityLeft);
        NeoForge.EVENT_BUS.addListener(WorldEvents::serverTick);
        NeoForge.EVENT_BUS.addListener(WorldEvents::playerPlaced);
        NeoForge.EVENT_BUS.addListener(WorldEvents::playerBreaking);
        NeoForge.EVENT_BUS.addListener(WorldEvents::playerChat);
        NeoForge.EVENT_BUS.addListener(WorldEvents::serverStarted);
        NeoForge.EVENT_BUS.addListener(WorldEvents::serverStopping);
        io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.initialize();
    }

    private void attributes(EntityAttributeCreationEvent event) {
        event.put(COMPANION.get(), LegacyCompanionEntity.attributes().build());
    }

    public static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(ID, name); }
}
