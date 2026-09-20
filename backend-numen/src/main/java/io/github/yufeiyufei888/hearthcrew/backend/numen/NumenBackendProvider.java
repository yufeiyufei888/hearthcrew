package io.github.yufeiyufei888.hearthcrew.backend.numen;

import io.github.yufeiyufei888.hearthcrew.backend.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Host-facing factory. No hidden legacy body and no automatic takeover of existing players. */
public final class NumenBackendProvider implements BackendProvider {
    public String id(){return "numen";}
    public String version(){return "947f0064-hearthcrew-0.3.2";}
    public java.util.Set<io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind> capabilities(){return OrderAdapter.CAPABILITIES;}
    public void initialize(){BackendRuntime.initialize();}
    public void configure(MinecraftServer server,Protection protection){BackendProtection.configure(server,protection::positions);}
    public UUID worldId(MinecraftServer server){return BackendJournal.get(server).worldId();}
    public boolean owns(UUID id){return NumenBackend.owns(id);}
    public void publishFacility(ServerLevel level,net.minecraft.core.BlockPos p,boolean shared){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Server thread required");
        if(!level.hasChunkAt(p)||!FacilityAccess.isFacility(level.getBlockState(p)))throw new IllegalArgumentException("FACILITY_MISSING_OR_NOT_LOADED");
        var journal=BackendJournal.get(level.getServer());if(shared)journal.publish(level.dimension(),p);else journal.unpublish(level.dimension(),p);
        level.getServer().overworld().getDataStorage().save();
    }
    public java.util.Map<String,Object> inspectFacility(net.minecraft.server.level.ServerPlayer body,net.minecraft.core.BlockPos p){return FacilityInspection.observe(body,p);}
    public CompanionBackend create(ServerLevel level,UUID id,String name,UUID owner,Vec3 position,boolean restore){return NumenBackend.spawn(level,id,name,owner,position,restore);}
}
