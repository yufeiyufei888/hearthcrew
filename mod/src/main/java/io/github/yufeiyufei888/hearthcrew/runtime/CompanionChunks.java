package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Bounded, server-owned loading around the three registered companions. */
public final class CompanionChunks implements AutoCloseable {
    public static final int MAX_COMPANIONS = 3;
    public static final int REGION_RADIUS = 3;
    private static final TicketType<UUID> TICKET = TicketType.create("hearthcrew_companion", Comparator.comparing(UUID::toString), 60);
    public record Anchor(String dimension, int chunkX, int chunkZ) {
        public Anchor {
            ResourceLocation.parse(Objects.requireNonNull(dimension));
            if (Math.abs((long)chunkX) > 1_875_000 || Math.abs((long)chunkZ) > 1_875_000)
                throw new IllegalArgumentException("companion chunk is outside the world border limit");
        }
        ChunkPos position() { return new ChunkPos(chunkX, chunkZ); }
    }
    private final MinecraftServer server;
    private final CrewWorldData data;
    private final Map<UUID, Anchor> active = new LinkedHashMap<>();
    private final Map<UUID, Integer> missingReadyTicks = new HashMap<>();
    private final Set<UUID> unavailable = new HashSet<>();
    private record Ahead(Anchor anchor,long expires) {}
    private final Map<UUID,List<Ahead>> ahead = new HashMap<>();
    private static final TicketType<UUID> AHEAD_TICKET=TicketType.create("hearthcrew_explore",Comparator.comparing(UUID::toString),80);
    public void requestAhead(CompanionEntity body,BlockPos target) {
        requireServerThread();if(closed||body.executor().stopped()||body.executor().paused())return;
        UUID id=body.getUUID();if(!ahead.containsKey(id)&&ahead.size()>=3)return;
        var prior=ahead.get(id);if(prior!=null&&prior.stream().allMatch(a->a.expires()-server.overworld().getGameTime()>40))return;
        releaseAhead(id);var direction=net.minecraft.world.phys.Vec3.atCenterOf(target).subtract(body.position()).normalize();var requests=new ArrayList<Ahead>();
        for(int distance:new int[]{16,32}) {
            var at=BlockPos.containing(body.position().add(direction.scale(Math.min(distance,body.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target))))));if(!body.level().getWorldBorder().isWithinBounds(at))continue;
            var chunk=new ChunkPos(at);var anchor=new Anchor(body.level().dimension().location().toString(),chunk.x,chunk.z);
            if(requests.stream().anyMatch(a->a.anchor().equals(anchor)))continue;
            body.serverLevel().getChunkSource().addRegionTicket(AHEAD_TICKET,chunk,2,id);requests.add(new Ahead(anchor,server.overworld().getGameTime()+60));
        }ahead.put(id,requests);
    }
    private void releaseAhead(UUID id) {var requests=ahead.remove(id);if(requests!=null)for(var request:requests){var level=level(request.anchor());if(level!=null)level.getChunkSource().removeRegionTicket(AHEAD_TICKET,request.anchor().position(),2,id);}}
    private long lastRenewTick = Long.MIN_VALUE;
    private boolean closed;

    public CompanionChunks(MinecraftServer server, CrewWorldData data) {
        this.server = Objects.requireNonNull(server); this.data = Objects.requireNonNull(data);
    }
    public boolean track(CompanionEntity body) {
        requireServerThread();
        if (closed || body.getServer() != server || !body.isAlive()
                || !(body.level() instanceof ServerLevel level) || level.getEntity(body.getUUID()) != body) return false;
        Anchor next = anchor(body);
        if (!data.rememberChunkAnchor(body.companionId(), next)) return false;
        unavailable.remove(body.companionId()); missingReadyTicks.remove(body.companionId());
        removeTicket(body.companionId()); // Native player tickets now own body loading.
        return true;
    }
    public void tick() {
        requireServerThread();
        if (closed || data.chunkRecoveryInvalid()) return;
        Map<UUID, CompanionEntity> live = new HashMap<>();
        for (CompanionEntity body : CrewWorldData.liveCompanions(server)) live.put(body.companionId(), body);
        long tick = server.overworld().getGameTime();
        // A mount must tick before its rider can update native player tickets.
        // Renew through the existing bounded ahead-ticket pool from the server clock,
        // so an edge-of-chunk boarding cannot wait forever for its own passenger tick.
        for(var body:live.values())if(body.isPassenger()&&body.executor().hasOrdinaryWork())requestAhead(body,body.getVehicle().blockPosition());
        for(var id:List.copyOf(ahead.keySet())){var body=live.get(id);if(body==null||body.executor().stopped()||body.executor().paused()||!body.executor().hasOrdinaryWork()||ahead.get(id).stream().anyMatch(a->a.expires()<tick))releaseAhead(id);}
        boolean renew = lastRenewTick == Long.MIN_VALUE || tick - lastRenewTick >= 20;
        for (var entry : data.chunkAnchors().entrySet()) {
            UUID id = entry.getKey(); Anchor next = entry.getValue();
            CompanionEntity body = live.get(id);
            if (body != null) {
                next = anchor(body); data.rememberChunkAnchor(id, next);
                unavailable.remove(id); missingReadyTicks.remove(id);removeTicket(id);continue;
            } else if (unavailable.contains(id)) continue;
            updateTicket(id, next, renew);
            ServerLevel level = level(next);
            if (body == null && !data.hasPendingRespawn(id) && level != null
                    && level.isPositionEntityTicking(new BlockPos(next.chunkX() * 16 + 8, 64, next.chunkZ() * 16 + 8))) {
                if (missingReadyTicks.merge(id, 1, Integer::sum) >= 40) {
                    // Keep the saved anchor for diagnosis/reconciliation. Do
                    // not invent a replacement body or force an empty region forever.
                    unavailable.add(id); removeTicket(id);
                }
            }
        }
        if (renew) lastRenewTick = tick;
    }
    public Map<String, Object> diagnostics() {
        return Map.of("maxCompanions", MAX_COMPANIONS, "regionRadius", REGION_RADIUS,"bodyLoading","native_player_tickets","nativePlayers",server.getPlayerList().getPlayers().stream().filter(p->p instanceof CompanionEntity).count(),
                "registered", data.chunkAnchors().size(), "activeTickets", active.size(),
                "unavailable", unavailable.stream().map(UUID::toString).sorted().toList(),
                "recoveryInvalid", data.chunkRecoveryInvalid(), "closed", closed);
    }
    private void updateTicket(UUID id, Anchor next, boolean renew) {
        Anchor before = active.get(id);
        if (!next.equals(before)) {
            removeTicket(id);
            ServerLevel level = level(next);
            if (level == null || active.size() >= MAX_COMPANIONS) { unavailable.add(id); return; }
            level.getChunkSource().addRegionTicket(TICKET, next.position(), REGION_RADIUS, id);
            active.put(id, next);
        } else if (renew) {
            ServerLevel level = level(next);
            if (level != null) level.getChunkSource().addRegionTicket(TICKET, next.position(), REGION_RADIUS, id);
        }
    }
    private void removeTicket(UUID id) {
        Anchor previous = active.remove(id);
        if (previous == null) return;
        ServerLevel level = level(previous);
        if (level != null) level.getChunkSource().removeRegionTicket(TICKET, previous.position(), REGION_RADIUS, id);
    }
    private ServerLevel level(Anchor anchor) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(anchor.dimension()));
        return server.getLevel(dimension);
    }
    private static Anchor anchor(CompanionEntity body) {
        return new Anchor(body.level().dimension().location().toString(), body.chunkPosition().x, body.chunkPosition().z);
    }
    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("companion chunk tickets belong to the server thread");
    }
    @Override public void close() {
        requireServerThread(); closed = true;
        for (UUID id : List.copyOf(active.keySet())) removeTicket(id);
        for (UUID id : List.copyOf(ahead.keySet())) releaseAhead(id);
        missingReadyTicks.clear(); unavailable.clear();
    }
}
