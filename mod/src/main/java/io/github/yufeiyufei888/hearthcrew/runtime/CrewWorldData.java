package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.gameplay.CompanionRespawn;
import io.github.yufeiyufei888.hearthcrew.kernel.FoodState;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** World identity and death recovery are authoritative game data, never controller guesses. */
public final class CrewWorldData extends SavedData {
    private final UUID worldId;
    private final Map<String,String> recoveryOperations=new LinkedHashMap<>();
    private final Map<UUID, CompoundTag> pendingRespawns = new LinkedHashMap<>();
    private final Set<String> playerBlocks = new HashSet<>();
    private final Map<String,Set<BlockPos>> protectionCache=new HashMap<>();
    private final Map<String, String> placementOwners = new HashMap<>();
    private final Map<UUID, CompanionChunks.Anchor> chunkAnchors = new LinkedHashMap<>();
    /** Per-owner control state. Missing entries deliberately mean autonomous and active. */
    private final Map<UUID, ControlState> controls = new LinkedHashMap<>();
    private Tag invalidChunkAnchors;
    private Tag invalidControls;
    private CompoundTag teamArchive;
    private record ControlState(boolean autonomyEnabled, boolean standby, long controlRevision) {
        private ControlState {
            if (controlRevision < 0) throw new IllegalArgumentException("negative control revision");
        }
    }
    public CrewWorldData() { this.worldId = UUID.randomUUID(); setDirty(); }
    private CrewWorldData(CompoundTag tag) {
        worldId = tag.hasUUID("worldId") ? tag.getUUID("worldId") : UUID.randomUUID();
        var repairs=tag.getCompound("recoveryOperations");for(var key:repairs.getAllKeys())recoveryOperations.put(key,repairs.getString(key));
        if (tag.contains("team")) {
            // Preserve malformed archives as an invalid wrapper, never silently reset shared ownership.
            teamArchive = tag.contains("team", Tag.TAG_COMPOUND) ? tag.getCompound("team").copy() : new CompoundTag();
            if (!tag.contains("team", Tag.TAG_COMPOUND)) teamArchive.put("invalidOriginal", tag.get("team").copy());
        }
        for (Tag item : tag.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag pending = (CompoundTag)item;
            if (pending.hasUUID("identity")) pendingRespawns.put(pending.getUUID("identity"), pending.copy());
        }
        for (Tag item : tag.getList("playerBlocks", Tag.TAG_STRING)) playerBlocks.add(item.getAsString());
        for (Tag raw : tag.getList("placementOwners", Tag.TAG_COMPOUND)) {
            var row = (CompoundTag)raw;
            placementOwners.put(row.getString("position"), row.getString("owner"));
        }
        if (tag.contains("chunkAnchors")) {
            Tag rawAnchors = tag.get("chunkAnchors");
            ListTag anchors = tag.getList("chunkAnchors", Tag.TAG_COMPOUND);
            try {
                if (!(rawAnchors instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                        || anchors.size() > CompanionChunks.MAX_COMPANIONS)
                    throw new IllegalArgumentException("invalid companion chunk registry");
                for (Tag item : anchors) {
                    CompoundTag saved = (CompoundTag)item;
                    if (!saved.hasUUID("identity") || !saved.contains("dimension", Tag.TAG_STRING)
                            || !saved.contains("x", Tag.TAG_INT) || !saved.contains("z", Tag.TAG_INT))
                        throw new IllegalArgumentException("invalid companion chunk anchor");
                    if (chunkAnchors.putIfAbsent(saved.getUUID("identity"), new CompanionChunks.Anchor(saved.getString("dimension"), saved.getInt("x"), saved.getInt("z"))) != null)
                        throw new IllegalArgumentException("duplicate companion chunk anchor");
                }
            } catch (RuntimeException invalid) { chunkAnchors.clear(); invalidChunkAnchors = rawAnchors.copy(); }
        }
        if (tag.contains("controls")) {
            Tag rawControls = tag.get("controls");
            try {
                ListTag entries = tag.getList("controls", Tag.TAG_COMPOUND);
                if (!(rawControls instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                        || entries.size() > CompanionChunks.MAX_COMPANIONS)
                    throw new IllegalArgumentException("invalid companion control registry");
                for (Tag item : entries) {
                    CompoundTag saved = (CompoundTag)item;
                    if (!saved.hasUUID("identity") || !saved.contains("autonomy", Tag.TAG_BYTE)
                            || !saved.contains("standby", Tag.TAG_BYTE) || !saved.contains("revision", Tag.TAG_LONG))
                        throw new IllegalArgumentException("invalid companion control state");
                    UUID identity = saved.getUUID("identity");
                    if (controls.putIfAbsent(identity, new ControlState(saved.getBoolean("autonomy"), saved.getBoolean("standby"), saved.getLong("revision"))) != null)
                        throw new IllegalArgumentException("duplicate companion control state");
                }
            } catch (RuntimeException invalid) { controls.clear(); invalidControls = rawControls.copy(); }
        }
    }
    public static CrewWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CrewWorldData::new, (tag, provider) -> new CrewWorldData(tag)), "hearthcrew_world");
    }
    public String recoverLegacyStandby(String requestId,CompanionEntity body,long expectedRevision,String expectedName) {
        if(recoveryOperations.containsKey(requestId))return recoveryOperations.get(requestId);
        var id=body.companionId();
        String result;
        if(controlRevision(id)!=expectedRevision || !body.getDisplayName().getString().equals(expectedName) || !standby(id) || body.executor().stopped() || body.executor().paused() || body.executor().recoveryInvalid())result="SKIPPED_NEW_CONTROL_OR_UNCONFIRMED_BODY";
        else {setStandby(id,false);result="RECOVERY_ASSESSMENT_REQUIRED";}
        recoveryOperations.put(requestId,result);setDirty();return result;
    }
    public UUID worldId() { return worldId; }
    public CompoundTag teamArchive() { return teamArchive == null ? null : teamArchive.copy(); }
    public void storeTeamArchive(CompoundTag archive) { teamArchive = Objects.requireNonNull(archive).copy(); setDirty(); }
    public int pendingRespawnCount() { return pendingRespawns.size(); }
    public boolean hasPendingRespawn(UUID identity) { return pendingRespawns.containsKey(identity); }
    public boolean chunkRecoveryInvalid() { return invalidChunkAnchors != null; }
    public Map<UUID, CompanionChunks.Anchor> chunkAnchors() { return Map.copyOf(chunkAnchors); }
    /** Missing state defaults to enabled autonomy and active play. */
    public boolean autonomyEnabled(UUID identity) {
        Objects.requireNonNull(identity, "identity");
        if (invalidControls != null) return false;
        return controls.getOrDefault(identity, new ControlState(true, false, 0)).autonomyEnabled();
    }
    public boolean standby(UUID identity) {
        Objects.requireNonNull(identity, "identity");
        if (invalidControls != null) return true;
        return controls.getOrDefault(identity, new ControlState(true, false, 0)).standby();
    }
    public long controlRevision(UUID identity) {
        Objects.requireNonNull(identity, "identity");
        return controls.getOrDefault(identity, new ControlState(true, false, 0)).controlRevision();
    }
    public void setAutonomy(UUID identity, boolean enabled) {
        updateControl(Objects.requireNonNull(identity, "identity"), enabled, standby(identity));
    }
    public void setStandby(UUID identity, boolean standby) {
        updateControl(Objects.requireNonNull(identity, "identity"), autonomyEnabled(identity), standby);
    }
    /** Invalidates an in-flight controller intent without changing the mode. */
    public void touchControl(UUID identity) {
        identity = Objects.requireNonNull(identity, "identity");
        if (invalidControls != null) throw new IllegalStateException("companion control state is unreadable");
        ControlState previous = controls.getOrDefault(identity, new ControlState(true, false, 0));
        if (previous.controlRevision() == Long.MAX_VALUE) throw new IllegalStateException("companion control revision exhausted");
        controls.put(identity, new ControlState(previous.autonomyEnabled(), previous.standby(), previous.controlRevision() + 1));
        setDirty();
    }
    private void updateControl(UUID identity, boolean autonomy, boolean standby) {
        if (invalidControls != null) throw new IllegalStateException("companion control state is unreadable");
        ControlState previous = controls.getOrDefault(identity, new ControlState(true, false, 0));
        if (previous.autonomyEnabled() == autonomy && previous.standby() == standby) return;
        if (previous.controlRevision() == Long.MAX_VALUE) throw new IllegalStateException("companion control revision exhausted");
        controls.put(identity, new ControlState(autonomy, standby, previous.controlRevision() + 1));
        setDirty();
    }
    public boolean rememberChunkAnchor(UUID identity, CompanionChunks.Anchor anchor) {
        Objects.requireNonNull(identity); Objects.requireNonNull(anchor);
        if (chunkRecoveryInvalid() || (!chunkAnchors.containsKey(identity) && chunkAnchors.size() >= CompanionChunks.MAX_COMPANIONS)) return false;
        if (!anchor.equals(chunkAnchors.put(identity, anchor))) setDirty();
        return true;
    }
    public void markPlayerBlock(Level level, BlockPos position) { playerBlocks.add(key(level, position)); protectionCache.clear();setDirty(); }
    public void markPlacement(Level level, BlockPos position, UUID identity, boolean crew) {
        String key = key(level,position);
        // Legacy coordinates remain protected even when attribution is unavailable.
        io.github.yufeiyufei888.hearthcrew.kernel.PlacementOwnership.placed(playerBlocks,placementOwners,key,identity.toString(),crew); protectionCache.clear();setDirty();
    }
    public String placementOwner(Level level, BlockPos position) { return placementOwners.get(key(level,position)); }
    public void confirmPlayerRemoval(Level level, BlockPos position, String expectedOwner) {
        String key = key(level,position);
        if (io.github.yufeiyufei888.hearthcrew.kernel.PlacementOwnership.removed(playerBlocks,placementOwners,key,expectedOwner)){protectionCache.clear();setDirty();}
    }
    public String protectionReason(Level level, BlockPos position) {
        if (!playerBlock(level,position)) return "unprotected";
        String owner = placementOwners.get(key(level,position));
        return owner != null && owner.startsWith("player:") ? "recorded_player_placement" : "legacy_or_unknown_protected_position";
    }
    public boolean playerBlock(Level level, BlockPos position) { return playerBlocks.contains(key(level, position)); }
    public Set<BlockPos> protectedPositions(Level level){String prefix=level.dimension().location()+"/";return protectionCache.computeIfAbsent(prefix,k->{var result=new HashSet<BlockPos>();for(String p:playerBlocks)if(p.startsWith(prefix))result.add(BlockPos.of(Long.parseLong(p.substring(prefix.length()))));return Set.copyOf(result);});}
    private static String key(Level level, BlockPos pos) { return level.dimension().location() + "/" + pos.asLong(); }

    public void recordDeath(CompanionEntity body) {
        CompoundTag state = new CompoundTag(); body.addAdditionalSaveData(state);
        CompoundTag pending = new CompoundTag();
        pending.putUUID("identity", body.companionId());
        pending.putUUID("previousEntity", body.getUUID());
        pending.put("body", state);
        pending.putString("name", body.getDisplayName().getString());
        pending.putLong("due", body.getServer().overworld().getGameTime() + 21);
        pendingRespawns.putIfAbsent(body.companionId(), pending);
        setDirty();
    }

    public void tick(MinecraftServer server) {
        // Legacy pending deaths remain archived until migration can establish identity.
        // Live players use PlayerList.respawn; never execute the old Mob respawn writer.
    }

    public static List<CompanionEntity> liveCompanions(MinecraftServer server) {
        List<CompanionEntity> result = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) for (Entity entity : level.getAllEntities())
            if (entity instanceof CompanionEntity body && body.isAlive()) result.add(body);
        return result;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putUUID("worldId", worldId);
        var repairs=new CompoundTag();recoveryOperations.forEach(repairs::putString);tag.put("recoveryOperations",repairs);
        if (teamArchive != null) tag.put("team", teamArchive.copy());
        ListTag pending = new ListTag(); pendingRespawns.values().forEach(value -> pending.add(value.copy())); tag.put("pending", pending);
        ListTag owners = new ListTag(); placementOwners.forEach((position,owner) -> {var row = new CompoundTag(); row.putString("position",position);row.putString("owner",owner);owners.add(row);}); tag.put("placementOwners",owners);
        ListTag blocks = new ListTag(); playerBlocks.forEach(value -> blocks.add(StringTag.valueOf(value))); tag.put("playerBlocks", blocks);
        if (invalidChunkAnchors != null) tag.put("chunkAnchors", invalidChunkAnchors.copy());
        else {
            ListTag anchors = new ListTag();
            chunkAnchors.forEach((id, anchor) -> {
                CompoundTag saved = new CompoundTag(); saved.putUUID("identity", id); saved.putString("dimension", anchor.dimension());
                saved.putInt("x", anchor.chunkX()); saved.putInt("z", anchor.chunkZ()); anchors.add(saved);
            });
            tag.put("chunkAnchors", anchors);
        }
        if (invalidControls != null) tag.put("controls", invalidControls.copy());
        else {
            ListTag savedControls = new ListTag();
            controls.forEach((identity, state) -> {
                CompoundTag saved = new CompoundTag(); saved.putUUID("identity", identity);
                saved.putBoolean("autonomy", state.autonomyEnabled()); saved.putBoolean("standby", state.standby());
                saved.putLong("revision", state.controlRevision()); savedControls.add(saved);
            });
            tag.put("controls", savedControls);
        }
        return tag;
    }
}
