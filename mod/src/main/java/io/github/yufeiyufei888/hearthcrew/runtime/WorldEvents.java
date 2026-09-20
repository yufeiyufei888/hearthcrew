package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.backend.BackendWorld;
import io.github.yufeiyufei888.hearthcrew.gameplay.CompanionTargeting;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.nio.file.Path;

/** Extends enemy target discovery. These are enemies' AI, not companion world writers. */
public final class WorldEvents {
    private record Removal(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
                           net.minecraft.world.level.block.state.BlockState before, String owner) {}
    private record Placement(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState placed, java.util.UUID identity, boolean crew) {}
    private static final java.util.List<Placement> placements = new java.util.ArrayList<>();
    private static final java.util.List<Removal> removals = new java.util.ArrayList<>();
    private static ModLink link;
    private static CompanionChunks chunks;
    private static CrewTeamService team;
    private static GameUiService ui;
    /** Read-only connection health for diagnostics; it does not imply action success. */
    public static boolean controllerConnected() { return link != null && link.ready(); }
    public static boolean trackCompanion(CompanionEntity body) { return chunks != null && chunks.track(body); }
    /** Shared owner control path for local slash/UI and the authenticated bridge. */
    public static void controlBody(CompanionEntity body, String operation) {
        CrewWorldData data = CrewWorldData.get(body.getServer()); var id = body.companionId();
        switch (operation) {
            case "stop" -> { body.executor().stop("owner stop"); data.touchControl(id); team().ownerRetiredWork(body); }
            case "pause" -> { body.executor().pause(); data.touchControl(id); }
            case "resume" -> { data.setStandby(id, false); body.executor().resume(); data.touchControl(id); }
            case "retask" -> { body.executor().retask(); data.setStandby(id, false); data.touchControl(id); team().ownerRetiredWork(body); }
            case "auto_on" -> { data.setAutonomy(id, true); data.setStandby(id, false); }
            case "auto_off" -> { data.setAutonomy(id, false); body.executor().disableAutonomy(); team().retireAutonomousWork(body); }
            case "standby" -> { data.setStandby(id, true); body.executor().standby(); team().retireOrdinaryWork(body); }
            default -> throw new IllegalArgumentException("unsupported control");
        }
    }
    public static CrewTeamService team() {
        if (team == null) throw new IllegalStateException("team service unavailable while world is closed");
        return team;
    }
    public static java.util.Map<String, Object> chunkDiagnostics() {
        return chunks == null ? java.util.Map.of("state", "world_closed") : chunks.diagnostics();
    }
    public static void requestExplorationChunks(CompanionEntity body,net.minecraft.core.BlockPos target){if(chunks!=null)chunks.requestAhead(body,target);}
    public static void serverStarted(ServerStartedEvent event) {
        BackendWorld.start(event.getServer());
        chunks = new CompanionChunks(event.getServer(), CrewWorldData.get(event.getServer()));
        team = new CrewTeamService(event.getServer(), CrewWorldData.get(event.getServer()));
        String pairing = System.getProperty("hearthcrew.pairing");
        if (!Boolean.getBoolean("hearthcrew.isolatedGameTest") && (pairing == null || pairing.isBlank())) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) pairing = Path.of(localAppData, "HearthCrew", "runtime", "pairing.json").toString();
        }
        if ((!BackendWorld.enabled()||BackendWorld.fault(event.getServer()).isEmpty()) && pairing != null && !pairing.isBlank()) link = new ModLink(event.getServer(), Path.of(pairing));
        ui = new GameUiService(event.getServer(), () -> link == null ? 0L : link.sessionEpoch(),
                () -> link != null && link.ready(), details -> link != null && link.publishUiRequest(details));
        io.github.yufeiyufei888.hearthcrew.network.UiNetwork.setServerHandler(ui::handleRequest);
        if (link != null) { link.setUiService(ui); link.start(); }
        if(!BackendWorld.enabled())CrewPlayers.get(event.getServer()).restore(event.getServer());
    }
    public static void serverStopping(ServerStoppingEvent event) {
        io.github.yufeiyufei888.hearthcrew.entity.BodyExecutor.clearSpaceLeases();
        io.github.yufeiyufei888.hearthcrew.network.UiNetwork.setServerHandler(null);
        if (ui != null) { ui.close(); ui = null; }
        if (link != null) { link.close(); link = null; }
        if (chunks != null) { chunks.close(); chunks = null; }
        if(BackendWorld.enabled())BackendWorld.close(event.getServer());else CrewPlayers.get(event.getServer()).close(event.getServer());
        NearbyObservation.clear();
        team = null; removals.clear(); placements.clear();
    }
    public static void serverTick(ServerTickEvent.Post event) {
        if(BackendWorld.enabled())BackendWorld.tick(event.getServer());
        for (var removal : removals) if (!removal.level().getBlockState(removal.pos()).equals(removal.before()))
            CrewWorldData.get(event.getServer()).confirmPlayerRemoval(removal.level(),removal.pos(),removal.owner());
        removals.clear();
        for(var placement:placements) if(placement.level().getBlockState(placement.pos()).equals(placement.placed()))
            CrewWorldData.get(event.getServer()).markPlacement(placement.level(),placement.pos(),placement.identity(),placement.crew());
        placements.clear();
        NearbyObservation.tick(event.getServer());
        if (chunks != null) chunks.tick();
        CrewWorldData.get(event.getServer()).tick(event.getServer());
        if (team != null) team.tick();
        if (ui != null) ui.tick();
        if (link != null) link.tick();
    }
    public static void playerPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        var crew = CrewWorldData.liveCompanions(player.getServer()).stream().filter(b -> b.executor().ownsInteractionContext(player)).findFirst();
        placements.add(new Placement(player.serverLevel(),event.getPos().immutable(),event.getPlacedBlock(),crew.map(CompanionEntity::companionId).orElse(player.getUUID()),crew.isPresent()||BackendWorld.owns(player.getUUID())));
    }
    public static void playerBreaking(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || event.getPlayer() instanceof net.neoforged.neoforge.common.util.FakePlayer
                || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var data = CrewWorldData.get(level.getServer());
        removals.add(new Removal(level,event.getPos().immutable(),event.getState(),data.placementOwner(level,event.getPos())));
    }
    public static void playerChat(net.neoforged.neoforge.event.ServerChatEvent event) {
        if (event.isCanceled() || link == null) return;
        ServerPlayer player = event.getPlayer();
        String message = event.getRawText().strip();
        if(BackendWorld.enabled()){
            var targets=BackendWorld.roster(player.server).members().stream().filter(m->m.owner().equals(player.getUUID())&&(message.matches("(?s)^@?小队(?=[\\s,，:：]|$).*")||message.matches("(?isu)^@?"+java.util.regex.Pattern.quote(m.name())+"(?=[\\s,，:：]|$).*"))).toList();
            if(!targets.isEmpty()&&!link.publishPlayerChat(java.util.Map.of("messageId",java.util.UUID.randomUUID().toString(),"ownerId",player.getUUID().toString(),"ownerName",player.getGameProfile().getName(),"recipientIds",targets.stream().map(m->m.id().toString()).toList(),"recipientNames",targets.stream().map(m->m.name()).toList(),"message",message)))
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[炉火伙伴] 消息队列暂不可用，请连接后重试。"));
            return;
        }
        var owned = CrewWorldData.liveCompanions(player.getServer()).stream()
                .filter(body -> player.getUUID().equals(body.ownerId())).toList();
        var targets = new java.util.ArrayList<CompanionEntity>();
        boolean teamMention = message.matches("(?s)^@?小队(?=[\\s,，:：]|$).*");
        for (var body : owned) {
            String name = java.util.regex.Pattern.quote(body.getDisplayName().getString());
            if (teamMention || message.matches("(?isu)^@?" + name + "(?=[\\s,，:：]|$).*")) targets.add(body);
        }
        if (targets.isEmpty()) return;
        if (!teamMention && targets.size() > 1) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[炉火伙伴] 名字不唯一，请使用不同的伙伴名字，或明确 @小队。")); return;
        }
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("messageId", java.util.UUID.randomUUID().toString());
        details.put("ownerId", player.getUUID().toString()); details.put("ownerName", player.getGameProfile().getName());
        details.put("recipientIds", targets.stream().map(body -> body.companionId().toString()).toList());
        details.put("recipientNames", targets.stream().map(body -> body.getDisplayName().getString()).toList());
        details.put("message", message);
        if (!link.publishPlayerChat(details)) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[炉火伙伴] 消息队列暂不可用，请连接后重试。"));
    }
    public static void entityLeft(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event){
        if(event.getLevel().isClientSide()||!(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item))return;
        var reason=item.getRemovalReason();if(reason!=net.minecraft.world.entity.Entity.RemovalReason.DISCARDED&&reason!=net.minecraft.world.entity.Entity.RemovalReason.KILLED)return;
        for(var body:CrewWorldData.liveCompanions(event.getLevel().getServer()))body.executor().observedDropRemoval(item.getUUID(),"entity_removal:"+reason.name());
    }
    public static void entityJoined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if(BackendWorld.enabled()){
            if(event.getEntity() instanceof ServerPlayer player&&!BackendWorld.owns(player.getUUID())&&!(player instanceof CompanionEntity)&&!(player instanceof net.neoforged.neoforge.common.util.FakePlayer)&&!Boolean.getBoolean("hearthcrew.isolatedGameTest"))
                player.server.execute(()->{try{BackendWorld.join(player);}catch(RuntimeException error){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[炉火伙伴] "+error.getMessage()));}});
            return;
        }
        if (event.getEntity() instanceof io.github.yufeiyufei888.hearthcrew.entity.LegacyCompanionEntity legacy)
            event.getLevel().getServer().execute(() -> {try{CrewPlayers.get(legacy.getServer()).migrate(legacy);}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(WorldEvents.class).error("Legacy companion preserved after migration refusal: {}",error.toString());}});
        else if (!Boolean.getBoolean("hearthcrew.isolatedGameTest") && event.getEntity() instanceof ServerPlayer player && !(player instanceof CompanionEntity)
                && !(player instanceof net.neoforged.neoforge.common.util.FakePlayer))
            player.getServer().execute(() -> {try{CrewPlayers.get(player.getServer()).initializeFor(player);}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(WorldEvents.class).error("Companion initialization refused: {}",error.toString());}});
    }
}
