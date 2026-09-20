package io.github.yufeiyufei888.hearthcrew.backend;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Optional runtime service; loading it does not authorize world creation or migration. */
public interface BackendProvider {
    @FunctionalInterface interface Protection { Set<BlockPos> positions(ServerLevel level); }
    String id();
    String version();
    Set<io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind> capabilities();
    void initialize();
    void configure(MinecraftServer server,Protection protection);
    UUID worldId(MinecraftServer server);
    boolean owns(UUID id);
    /** Explicit host/player authorization, never a model tool or a grant to break the block. */
    void publishFacility(ServerLevel level,BlockPos position,boolean shared);
    /** Read-only, loaded and interactable public facilities only; no menu/loot activation. */
    Map<String,Object> inspectFacility(net.minecraft.server.level.ServerPlayer body,BlockPos position);
    CompanionBackend create(ServerLevel level,UUID id,String name,UUID owner,Vec3 position,boolean restore);
}
