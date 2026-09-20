package io.github.yufeiyufei888.hearthcrew.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import io.github.yufeiyufei888.hearthcrew.runtime.WorldEvents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlled real-client P2 team fixture.  This class creates evidence only:
 * it never submits an ActionArbiter order or a TeamLedger task.  The model and
 * controller must perform every mine, craft, transfer, and placement observed
 * in the reports.
 */
@EventBusSubscriber(modid = HearthCrew.ID, value = Dist.CLIENT)
public final class DevelopmentTeamFixture {
    public static final String ENABLE_PROPERTY = "hearthcrew.teamTest";
    public static final String RUN_ID_PROPERTY = "hearthcrew.teamRunId";
    public static final String DIRECTORY_PROPERTY = "hearthcrew.fixtureDirectory";
    public static final String BUILD_PROPERTY = "hearthcrew.teamBuild";
    public static final String GATHER_PROPERTY = "hearthcrew.teamGather";
    public static final String SAVE_PREFIX = "P2Team-";
    public static final String EVIDENCE = "CONTROLLED_REAL_CLIENT_TEAM_FIXTURE";
    public static final String BUILD_EVIDENCE = "CONTROLLED_REAL_CLIENT_TEAM_BUILD_FIXTURE";
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,48}");
    private static final ResourceLocation OAK_PLANKS = ResourceLocation.parse("minecraft:oak_planks");
    private static final GsonBuilder JSON = new GsonBuilder().setPrettyPrinting();
    private static final Logger LOGGER = LoggerFactory.getLogger(DevelopmentTeamFixture.class);

    private static boolean setupRequested;
    private static boolean invalidRunReported;
    private static boolean directoryReported;
    private static volatile boolean fixtureReady;
    private static volatile boolean monitorQueued;
    private static volatile String terminalStatus;
    private static String activeRunId;
    private static boolean activeBuildMode;
    private static boolean activeGatherMode;
    private static long lastReportTick = Long.MIN_VALUE;
    private static String lastReportFingerprint;
    private static UUID fixtureWorldId;
    private static UUID fixtureOwnerId;
    private static TeamBodies bodies;
    private static List<BlockPos> logPositions = List.of();
    private static List<BlockPos> buildingPositions = List.of();
    private static Path activeSave;
    private static int visibleTicks;
    private static boolean readyScreenshotTaken;
    private static boolean terminalScreenshotTaken;

    private DevelopmentTeamFixture() {}

    public static String saveName(String runId) {
        if (!isSafeRunId(runId)) throw new IllegalArgumentException("unsafe fixture run ID");
        return SAVE_PREFIX + runId;
    }

    public static boolean isSafeRunId(String runId) {
        return runId != null && SAFE_RUN_ID.matcher(runId).matches();
    }

    private static boolean buildMode() {
        return activeBuildMode;
    }

    private static String evidence() {
        if (activeGatherMode) return "CONTROLLED_REAL_CLIENT_GATHER_FIXTURE";
        return buildMode() ? BUILD_EVIDENCE : EVIDENCE;
    }

    private static String constructionMode() {
        if (activeGatherMode) return "GATHER_ONLY";
        return buildMode() ? "BUILD" : "PLACE";
    }

    /** Called by the bootstrap when it finds a stale save; never opens it. */
    static void logExistingSaveRefusal(Path save, String runId) {
        LOGGER.error("P2 team fixture refuses existing save {}; use a fresh {} run", save, runId);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) return;
        String runId = System.getProperty(RUN_ID_PROPERTY, "").trim();
        if (!isSafeRunId(runId)) {
            if (!invalidRunReported) {
                invalidRunReported = true;
                LOGGER.error("P2 team fixture requires -D{}=<1..48 safe run id>", RUN_ID_PROPERTY);
            }
            return;
        }
        Path expectedDirectory;
        try {
            String configured = System.getProperty(DIRECTORY_PROPERTY, "").trim();
            if (configured.isEmpty()) throw new IllegalArgumentException("missing fixture directory");
            expectedDirectory = Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            if (!directoryReported) {
                directoryReported = true;
                LOGGER.error("P2 team fixture refuses an invalid -D{}", DIRECTORY_PROPERTY, error);
            }
            return;
        }
        Path actualDirectory = client.gameDirectory.toPath().toAbsolutePath().normalize();
        Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!actualDirectory.equals(expectedDirectory)
                || !save.equals(expectedDirectory.resolve("saves").resolve(saveName(runId)).normalize())) return;
        if (activeRunId != null && !activeRunId.equals(runId)) {
            LOGGER.error("P2 team fixture refuses to mix run IDs: old={}, new={}", activeRunId, runId);
            return;
        }
        if (activeRunId == null) {
            activeRunId = runId;
            activeBuildMode = Boolean.getBoolean(BUILD_PROPERTY);
            activeGatherMode = Boolean.getBoolean(GATHER_PROPERTY);
        }
        activeSave = save;
        if (fixtureReady && client.screen == null && visibleTicks < 20) visibleTicks++;
        if (fixtureReady && client.screen == null && visibleTicks >= 20 && !readyScreenshotTaken) {
            readyScreenshotTaken = true;
            Screenshot.grab(client.gameDirectory, "hearthcrew-p2-team-ready-" + runId + ".png",
                    client.getMainRenderTarget(), message -> LOGGER.info("P2 team READY screenshot: {}", message));
        }
        if (terminalStatus != null) {
            if (client.screen == null && visibleTicks >= 20 && !terminalScreenshotTaken) {
                terminalScreenshotTaken = true;
                Screenshot.grab(client.gameDirectory, "hearthcrew-p2-team-" + terminalStatus.toLowerCase() + "-" + runId + ".png",
                        client.getMainRenderTarget(), message -> LOGGER.info("P2 team terminal screenshot: {}", message));
            }
            return;
        }
        if (!setupRequested) {
            setupRequested = true;
            server.execute(() -> setup(server, client.player.getUUID(), runId));
        }
        if (fixtureReady && !monitorQueued) {
            monitorQueued = true;
            server.execute(() -> {
                try { monitor(server, runId); }
                catch (RuntimeException error) {
                    LOGGER.error("P2 team fixture monitor failed", error);
                    publishTerminalFailure(save, server.overworld(), runId, "fixture monitor exception: " + error.getClass().getSimpleName());
                } finally { monitorQueued = false; }
            });
        }
    }

    private static void setup(MinecraftServer server, UUID owner, String runId) {
        ServerLevel level = server.overworld();
        Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path fixtureFile = save.resolve("hearthcrew-p2-team-fixture-" + runId + ".json");
        if (Files.exists(fixtureFile)) {
            LOGGER.error("P2 team fixture metadata already exists; fresh save/runId required: {}", fixtureFile);
            JsonObject rejection = new JsonObject();
            rejection.addProperty("evidence", evidence());
            rejection.addProperty("status", "REJECTED_EXISTING_EVIDENCE");
            rejection.addProperty("runId", runId);
            rejection.addProperty("reason", "fixture metadata already exists; no old world may be reused");
            try {
                Files.writeString(save.resolve("hearthcrew-p2-startup-rejected-" + UUID.randomUUID() + ".json"),
                        JSON.create().toJson(rejection), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException error) { LOGGER.error("Could not write P2 startup rejection record", error); }
            terminalStatus = "FAIL";
            return;
        }
        var player = server.getPlayerList().getPlayer(owner);
        if (player == null) {
            publishTerminalFailure(save, level, runId, "fixture owner player was unavailable during setup");
            return;
        }

        BlockPos origin = new BlockPos(0, 100, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 1; y <= 6; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        logPositions = List.of(origin.offset(4, 1, 3), origin.offset(4, 1, 8), origin.offset(11, 1, 8));
        buildingPositions = List.of(origin.offset(10, 1, 12), origin.offset(11, 1, 12), origin.offset(12, 1, 12));
        for (BlockPos log : logPositions) level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);
        if (activeGatherMode) for (BlockPos log : logPositions) {
            level.setBlock(log.below(), Blocks.DIRT.defaultBlockState(), 3);
            for (int dy = 1; dy < 3; dy++) level.setBlock(log.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
            for (BlockPos leaf : List.of(log.above(3), log.above(3).east(), log.above(3).west(), log.above(3).north(), log.above(3).south()))
                level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), 3);
        }
        for (BlockPos target : buildingPositions) level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        player.teleportTo(level, 8.5, 101, 14.5, 180, 15);

        CompanionEntity captain = createBody(level, owner, "P2_CAPTAIN_" + runId, 2.5, 101, 2.5, 0);
        CompanionEntity gatherer = createBody(level, owner, "P2_GATHERER_" + runId, 2.5, 101, 6.5, 1);
        CompanionEntity builder = createBody(level, owner, "P2_BUILDER_" + runId, 12.5, 101, 6.5, 2);
        if (captain == null || gatherer == null || builder == null
                || !level.addFreshEntity(captain) || !level.addFreshEntity(gatherer) || !level.addFreshEntity(builder)
                || !WorldEvents.trackCompanion(captain) || !WorldEvents.trackCompanion(gatherer) || !WorldEvents.trackCompanion(builder)) {
            publishTerminalFailure(save, level, runId, "could not create and register all three empty companions");
            return;
        }
        for (CompanionEntity body : List.of(captain, gatherer, builder))
            body.setRespawnPoint(level.dimension(), body.blockPosition());
        fixtureWorldId = CrewWorldData.get(server).worldId();
        fixtureOwnerId = owner;
        bodies = new TeamBodies(
                new BodyIdentity("captain", captain), new BodyIdentity("gatherer", gatherer), new BodyIdentity("builder", builder));
        JsonObject metadata = metadata(level, runId);
        try {
            writeCreateNewAtomically(fixtureFile, metadata);
            fixtureReady = true;
            LOGGER.info("P2 team fixture READY: runId={}, worldId={}, captain={}, gatherer={}, builder={}, logs={}, build={}",
                    runId, fixtureWorldId, captain.getUUID(), gatherer.getUUID(), builder.getUUID(), logPositions, buildingPositions);
        } catch (IOException error) {
            LOGGER.error("P2 team fixture metadata could not be written", error);
            publishTerminalFailure(save, level, runId, "fixture metadata write failed: " + error.getClass().getSimpleName());
        }
    }

    private static CompanionEntity createBody(ServerLevel level, UUID owner, String name,
                                               double x, double y, double z, int skin) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(level);
        if (body == null) return null;
        body.setOwner(owner);
        body.setCustomName(Component.literal(name));
        body.setCustomNameVisible(true);
        body.setSkinIndex(skin);
        body.moveTo(x, y, z, 0, 0);
        body.setYHeadRot(0);
        body.yBodyRot = 0;
        body.inventory().clearContent();
        body.setInvulnerable(false);
        body.setRespawnEnabled(true);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) body.setItemSlot(slot, ItemStack.EMPTY);
        return body;
    }

    private static void monitor(MinecraftServer server, String runId) {
        if (terminalStatus != null || bodies == null) return;
        ServerLevel level = server.overworld();
        UUID currentWorld = CrewWorldData.get(server).worldId();
        String failure = "";
        if (!Objects.equals(fixtureWorldId, currentWorld)) failure = "worldId changed during the controlled team run";
        List<CompanionEntity> live = new ArrayList<>();
        live.add(findBody(server, bodies.captain));
        live.add(findBody(server, bodies.gatherer));
        live.add(findBody(server, bodies.builder));
        if (failure.isEmpty() && live.stream().anyMatch(Objects::isNull)) failure = "a registered companion disappeared";
        if (failure.isEmpty() && live.stream().anyMatch(body -> !body.isAlive()))
            failure = "a companion died";
        if (failure.isEmpty() && (!identityMatches(bodies.captain, live.get(0))
                || !identityMatches(bodies.gatherer, live.get(1)) || !identityMatches(bodies.builder, live.get(2))))
            failure = "a companion entity identity or bodyGeneration changed";
        if (failure.isEmpty() && live.stream().anyMatch(body -> !Objects.equals(fixtureOwnerId, body.ownerId())))
            failure = "a companion owner changed from the actual player";
        if (failure.isEmpty()) {
            String receiptFailure = receiptFailure(live);
            if (receiptFailure != null) failure = receiptFailure;
        }
        if (activeGatherMode) { monitorGather(level, runId, live, failure); return; }
        boolean logsAir = logPositions.size() == 3 && logPositions.stream().allMatch(pos -> level.getBlockState(pos).isAir());
        boolean buildingPlanks = buildingPositions.size() == 3
                && buildingPositions.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        InventoryView captainItems = inventory(live.get(0));
        InventoryView gathererItems = inventory(live.get(1));
        InventoryView builderItems = inventory(live.get(2));
        ActionChecks checks = actions(live);
        boolean inventories = captainItems.empty() && gathererItems.empty() && builderItems.onlyOakPlanks(9);
        boolean passed = failure.isEmpty() && logsAir && buildingPlanks && inventories && checks.complete();
        if (!failure.isEmpty()) { publishTerminalFailure(activeSave, level, runId, failure, live, logsAir, checks, captainItems, gathererItems, builderItems); return; }
        if (passed) { publishTerminal(activeSave, level, runId, "PASS", "", live, logsAir, buildingPlanks, checks, captainItems, gathererItems, builderItems); return; }
        String fingerprint = "PENDING|" + logsAir + '|' + buildingPlanks + '|' + inventories + '|' + checks.fingerprint() + '|' + captainItems.total() + '|' + gathererItems.total() + '|' + builderItems.total();
        long tick = level.getGameTime();
        if (!fingerprint.equals(lastReportFingerprint) || tick - lastReportTick >= 20) {
            if (writeReport(activeSave, level, runId, "PENDING", "", live, logsAir, buildingPlanks, checks, captainItems, gathererItems, builderItems)) {
                lastReportFingerprint = fingerprint;
                lastReportTick = tick;
                LOGGER.info("P2 team fixture PENDING: runId={}, tick={}, logsAir={}, inventories={}, actions={}", runId, tick, logsAir, inventories, checks.fingerprint());
            }
        }
    }

    private static CompanionEntity findBody(MinecraftServer server, BodyIdentity identity) {
        return CrewWorldData.liveCompanions(server).stream()
                .filter(body -> identity.logical().equals(body.companionId()) && identity.entity().equals(body.getUUID()))
                .findFirst().orElse(null);
    }

    /** Observes the actual nine blocks and exact inventories; never changes the test outcome. */
    private static void monitorGather(ServerLevel level, String runId, List<CompanionEntity> live, String failure) {
        JsonObject report = metadata(level, runId);
        JsonArray observedBodies = new JsonArray();
        boolean passed = failure.isEmpty() && logPositions.size() == 3;
        for (BlockPos root : logPositions) for (int dy = 0; dy < 3; dy++) passed &= level.getBlockState(root.above(dy)).isAir();
        for (CompanionEntity body : live) {
            JsonObject result = new JsonObject();
            if (body == null) { passed = false; observedBodies.add(result); continue; }
            result.addProperty("entityId", body.getUUID().toString());
            result.addProperty("oakLogs", body.inventory().countItem(Items.OAK_LOG));
            Set<String> completed = matching(body, order -> order.kind() == BodyOrder.Kind.GATHER
                    && ResourceLocation.parse("minecraft:oak_log").equals(order.resource()) && order.count() == 3);
            result.add("completedGatherActions", JSON.create().toJsonTree(completed));
            result.add("journal", JSON.create().toJsonTree(body.executor().arbiter().journal()));
            int inventoryCount = 0;
            for (int slot = 0; slot < 36; slot++) inventoryCount += body.inventory().getItem(slot).getCount();
            passed &= completed.size() == 1 && body.inventory().countItem(Items.OAK_LOG) == 3 && inventoryCount == 3;
            observedBodies.add(result);
        }
        String state = !failure.isEmpty() ? "FAIL" : passed ? "PASS" : "PENDING";
        report.addProperty("status", state); report.addProperty("failure", failure);
        report.add("observedBodies", observedBodies);
        if (lastReportTick != Long.MIN_VALUE && level.getGameTime() - lastReportTick < 20 && "PENDING".equals(state)) return;
        lastReportTick = level.getGameTime();
        try {
            Files.writeString(activeSave.resolve("hearthcrew-p2-gather-report-" + runId + ".json"), JSON.create().toJson(report), StandardCharsets.UTF_8);
            if (!"PENDING".equals(state)) {
                terminalStatus = state;
                LOGGER.info("P2 GATHER fixture {}: runId={}, tick={}, reason={}", state, runId, level.getGameTime(), failure);
            }
        } catch (IOException error) { LOGGER.error("GATHER fixture report failed", error); }
    }

    private static boolean identityMatches(BodyIdentity expected, CompanionEntity observed) {
        return observed != null && expected.logical().equals(observed.companionId())
                && expected.entity().equals(observed.getUUID())
                && expected.generation() == Integer.toUnsignedLong(observed.getId());
    }

    private static String receiptFailure(List<CompanionEntity> bodies) {
        for (CompanionEntity body : bodies) for (var receipt : body.executor().arbiter().journal()) {
            if (receipt.state() == ActionState.FAILED || receipt.state() == ActionState.PARTIAL
                    || receipt.state() == ActionState.RECONCILE_REQUIRED)
                return "real body action reached " + receipt.state() + ": " + receipt.message();
        }
        return null;
    }

    private static ActionChecks actions(List<CompanionEntity> live) {
        Set<String> captainMine = matching(live.get(0), order -> order.kind() == BodyOrder.Kind.MINE && logPositions.get(0).equals(order.position()));
        Set<String> gathererMine = matching(live.get(1), order -> order.kind() == BodyOrder.Kind.MINE && logPositions.get(1).equals(order.position()));
        Set<String> builderMine = matching(live.get(2), order -> order.kind() == BodyOrder.Kind.MINE && logPositions.get(2).equals(order.position()));
        Set<String> captainCraft = matching(live.get(0), order -> craft(order));
        Set<String> gathererCraft = matching(live.get(1), order -> craft(order));
        Set<String> builderCraft = matching(live.get(2), order -> craft(order));
        UUID builderId = bodies.builder.entity();
        Set<String> captainTransfer = matching(live.get(0), order -> transfer(order, builderId));
        Set<String> gathererTransfer = matching(live.get(1), order -> transfer(order, builderId));
        Set<String> builderCompletedBuilds = matching(live.get(2), order -> order.kind() == BodyOrder.Kind.BUILD);
        Set<String> builderCompletedPlaces = matching(live.get(2), order -> order.kind() == BodyOrder.Kind.PLACE);
        Set<String> builderBuilds = matching(live.get(2), DevelopmentTeamFixture::matchingBuild);
        List<Set<String>> places = new ArrayList<>();
        if (!buildMode()) {
            for (BlockPos position : buildingPositions)
                places.add(matching(live.get(2), order -> order.kind() == BodyOrder.Kind.PLACE && position.equals(order.position())));
        }
        return new ActionChecks(captainMine, gathererMine, builderMine, captainCraft, gathererCraft, builderCraft,
                captainTransfer, gathererTransfer, places, builderBuilds, builderCompletedBuilds, builderCompletedPlaces);
    }

    private static boolean matchingBuild(BodyOrder order) {
        if (order.kind() != BodyOrder.Kind.BUILD || order.steps().size() != buildingPositions.size()) return false;
        Set<BlockPos> expected = new HashSet<>(buildingPositions);
        Set<BlockPos> actual = new HashSet<>();
        for (BodyOrder.BuildStep step : order.steps()) {
            if (!OAK_PLANKS.equals(step.block()) || !actual.add(step.position())) return false;
        }
        return actual.equals(expected);
    }

    private static boolean craft(BodyOrder order) {
        return order.kind() == BodyOrder.Kind.CRAFT && order.count() == 1 && OAK_PLANKS.equals(order.resource());
    }

    private static boolean transfer(BodyOrder order, UUID builder) {
        return order.kind() == BodyOrder.Kind.TRANSFER && order.count() == 4
                && builder.equals(order.target()) && OAK_PLANKS.equals(order.resource());
    }

    private static Set<String> matching(CompanionEntity body, java.util.function.Predicate<BodyOrder> predicate) {
        Set<String> result = new HashSet<>();
        if (body == null) return result;
        for (var receipt : body.executor().arbiter().journal())
            if (receipt.state() == ActionState.COMPLETED && receipt.payload() instanceof BodyOrder order && predicate.test(order))
                result.add(receipt.id().value());
        return result;
    }

    private static InventoryView inventory(CompanionEntity body) {
        int planks = 0, other = 0, total = 0;
        JsonArray stacks = new JsonArray();
        if (body == null) return new InventoryView(0, 1, 0, stacks);
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
            ItemStack stack = body.inventory().getItem(slot);
            if (stack.isEmpty()) continue;
            int amount = stack.getCount(); total += amount;
            if (stack.is(Items.OAK_PLANKS)) planks += amount; else other += amount;
            JsonObject item = new JsonObject(); item.addProperty("slot", slot); item.addProperty("item", String.valueOf(stack.getItem())); item.addProperty("count", amount); stacks.add(item);
        }
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            ItemStack stack = body.getItemBySlot(slot);
            if (!stack.isEmpty()) { other += stack.getCount(); total += stack.getCount(); }
        }
        return new InventoryView(planks, other, total, stacks);
    }

    private static JsonObject metadata(ServerLevel level, String runId) {
        JsonObject result = new JsonObject();
        result.addProperty("evidence", evidence()); result.addProperty("status", "READY"); result.addProperty("runId", runId);
        result.addProperty("constructionMode", constructionMode());
        result.addProperty("saveName", saveName(runId)); result.addProperty("worldId", fixtureWorldId.toString());
        result.addProperty("ownerUuid", fixtureOwnerId.toString());
        result.addProperty("serverTick", level.getGameTime()); result.addProperty("actionsSubmittedByFixture", false);
        result.addProperty("initialInventoryEmpty", true); result.addProperty("platformSize", 16);
        result.add("bodies", bodyMetadata());
        result.add("logs", positions(logPositions)); result.add("buildingPositions", positions(buildingPositions));
        result.addProperty("builderLogicalUuid", bodies.builder.logical().toString());
        result.addProperty("builderEntityUuid", bodies.builder.entity().toString());
        return result;
    }

    private static JsonArray bodyMetadata() {
        JsonArray result = new JsonArray();
        List<BodyIdentity> identities = List.of(bodies.captain, bodies.gatherer, bodies.builder);
        for (int index = 0; index < identities.size(); index++) {
            BodyIdentity identity = identities.get(index);
            JsonObject body = new JsonObject(); body.addProperty("role", identity.role()); body.addProperty("name", identity.name());
            body.addProperty("logicalUuid", identity.logical().toString()); body.addProperty("entityUuid", identity.entity().toString());
            body.addProperty("bodyGeneration", identity.generation()); body.addProperty("skinIndex", identity.skin());
            if (fixtureOwnerId != null) body.addProperty("ownerUuid", fixtureOwnerId.toString());
            if (index < logPositions.size()) body.add("oakPosition", position(logPositions.get(index)));
            body.addProperty("initialInventoryEmpty", true); result.add(body);
        }
        return result;
    }

    private static JsonArray positions(List<BlockPos> positions) {
        JsonArray result = new JsonArray();
        for (BlockPos position : positions) result.add(position(position));
        return result;
    }

    private static JsonObject position(BlockPos position) {
        JsonObject result = new JsonObject(); result.addProperty("x", position.getX()); result.addProperty("y", position.getY()); result.addProperty("z", position.getZ()); return result;
    }

    private static void publishTerminalFailure(Path save, ServerLevel level, String runId, String reason) {
        publishTerminalFailure(save, level, runId, reason, List.of(), false, new ActionChecks(), new InventoryView(0, 0, 0, new JsonArray()), new InventoryView(0, 0, 0, new JsonArray()), new InventoryView(0, 0, 0, new JsonArray()));
    }

    private static void publishTerminalFailure(Path save, ServerLevel level, String runId, String reason, List<CompanionEntity> live,
                                               boolean logsAir, ActionChecks checks, InventoryView captain, InventoryView gatherer, InventoryView builder) {
        publishTerminal(save, level, runId, "FAIL", reason, live, logsAir, false, checks, captain, gatherer, builder);
    }

    private static void publishTerminal(Path save, ServerLevel level, String runId, String status, String failure,
                                        List<CompanionEntity> live, boolean logsAir, boolean buildingPlanks, ActionChecks checks,
                                        InventoryView captain, InventoryView gatherer, InventoryView builder) {
        if (terminalStatus != null) return;
        if (!writeReport(save, level, runId, status, failure, live, logsAir, buildingPlanks, checks, captain, gatherer, builder)) return;
        terminalStatus = status;
        LOGGER.info("P2 team fixture {}: runId={}, tick={}, reason={}", status, runId, level.getGameTime(), failure);
    }

    private static boolean writeReport(Path save, ServerLevel level, String runId, String status, String failure,
                                       List<CompanionEntity> live, boolean logsAir, boolean buildingPlanks, ActionChecks checks,
                                       InventoryView captain, InventoryView gatherer, InventoryView builder) {
        JsonObject report = new JsonObject();
        report.addProperty("evidence", evidence()); report.addProperty("status", status); report.addProperty("runId", runId);
        report.addProperty("constructionMode", constructionMode());
        report.addProperty("saveName", saveName(runId)); report.addProperty("worldId", CrewWorldData.get(level.getServer()).worldId().toString());
        report.addProperty("expectedWorldId", fixtureWorldId == null ? "" : fixtureWorldId.toString());
        report.addProperty("ownerUuid", fixtureOwnerId == null ? "" : fixtureOwnerId.toString());
        report.addProperty("serverTick", level.getGameTime()); report.addProperty("failure", failure);
        report.addProperty("actionsSubmittedByFixture", false); report.addProperty("oakLogsAir", logsAir);
        report.addProperty("buildingPositionsOakPlanks", buildingPlanks);
        report.addProperty("builderNineOakPlanks", builder.onlyOakPlanks(9)); report.addProperty("unexpectedItems", !captain.empty() || !gatherer.empty() || !builder.onlyOakPlanks(9));
        report.add("logs", positions(logPositions)); report.add("buildingPositions", positions(buildingPositions));
        report.addProperty("buildId", checks.buildId());
        report.addProperty("completedBuildCount", checks.builderCompletedBuilds().size());
        report.addProperty("completedPlaceCount", checks.builderCompletedPlaces().size());
        report.add("contributions", contributionReport(live)); report.add("workChecks", checks.json());
        try { report.add("teamWorkState", JSON.create().toJsonTree(WorldEvents.team().snapshot())); }
        catch (RuntimeException error) { report.addProperty("teamWorkStateUnavailable", error.getClass().getSimpleName()); }
        report.add("captainInventory", captain.json()); report.add("gathererInventory", gatherer.json()); report.add("builderInventory", builder.json());
        Path destination = save.resolve("hearthcrew-p2-team-report-" + runId + ".json");
        Path temporary = save.resolve("hearthcrew-p2-team-report-" + runId + ".json.tmp");
        try {
            Files.writeString(temporary, JSON.create().toJson(report), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            LOGGER.error("P2 team fixture report write failed; retrying", error);
            return false;
        }
    }

    private static void writeCreateNewAtomically(Path destination, JsonObject value) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, JSON.create().toJson(value), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static JsonArray contributionReport(List<CompanionEntity> live) {
        JsonArray result = new JsonArray();
        if (bodies == null) return result;
        for (int index = 0; index < 3; index++) {
            BodyIdentity identity = bodies.at(index); CompanionEntity body = live.size() > index ? live.get(index) : null;
            JsonObject entry = new JsonObject(); entry.addProperty("role", identity.role()); entry.addProperty("name", identity.name());
            entry.addProperty("logicalUuid", identity.logical().toString()); entry.addProperty("entityUuid", identity.entity().toString());
            entry.addProperty("bodyGeneration", identity.generation()); entry.addProperty("alive", body != null && body.isAlive());
            if (fixtureOwnerId != null) entry.addProperty("ownerUuid", fixtureOwnerId.toString());
            entry.addProperty("completedMineCount", completedCount(body, BodyOrder.Kind.MINE));
            entry.addProperty("completedCraftCount", completedCount(body, BodyOrder.Kind.CRAFT));
            entry.addProperty("completedTransferCount", completedCount(body, BodyOrder.Kind.TRANSFER));
            entry.addProperty("completedPlaceCount", completedCount(body, BodyOrder.Kind.PLACE));
            entry.addProperty("completedBuildCount", completedCount(body, BodyOrder.Kind.BUILD));
            if (body != null) { entry.addProperty("observedEntityUuid", body.getUUID().toString()); entry.addProperty("dimension", body.level().dimension().location().toString()); entry.add("actionJournal", journal(body)); }
            result.add(entry);
        }
        return result;
    }

    private static int completedCount(CompanionEntity body, BodyOrder.Kind kind) {
        if (body == null) return 0;
        Set<String> ids = new HashSet<>();
        for (var receipt : body.executor().arbiter().journal()) {
            if (receipt.state() == ActionState.COMPLETED && receipt.payload() instanceof BodyOrder order && order.kind() == kind)
                ids.add(receipt.id().value());
        }
        return ids.size();
    }

    private static JsonArray journal(CompanionEntity body) {
        JsonArray result = new JsonArray();
        for (var receipt : body.executor().arbiter().journal()) {
            JsonObject row = new JsonObject(); row.addProperty("id", receipt.id().value()); row.addProperty("state", receipt.state().name()); row.addProperty("message", receipt.message());
            if (receipt.payload() instanceof BodyOrder order) { row.addProperty("kind", order.kind().name()); if (order.position() != null) row.add("position", position(order.position())); if (order.target() != null) row.addProperty("target", order.target().toString()); if (order.resource() != null) row.addProperty("resource", order.resource().toString()); row.addProperty("count", order.count()); }
            result.add(row);
        }
        return result;
    }

    private static final class TeamBodies {
        private final BodyIdentity captain, gatherer, builder;
        private TeamBodies(BodyIdentity captain, BodyIdentity gatherer, BodyIdentity builder) { this.captain = captain; this.gatherer = gatherer; this.builder = builder; }
        private BodyIdentity at(int index) { return switch (index) { case 0 -> captain; case 1 -> gatherer; default -> builder; }; }
    }

    private record BodyIdentity(String role, UUID logical, UUID entity, String name, long generation, int skin) {
        private BodyIdentity(String role, CompanionEntity body) { this(role, body.companionId(), body.getUUID(), body.getDisplayName().getString(), body.bodyGeneration(), body.skinIndex()); }
    }

    private record InventoryView(int planks, int other, int total, JsonArray stacks) {
        private boolean empty() { return total == 0; }
        private boolean onlyOakPlanks(int expected) { return planks == expected && other == 0; }
        private JsonObject json() { JsonObject result = new JsonObject(); result.addProperty("oakPlanks", planks); result.addProperty("otherItems", other); result.addProperty("total", total); result.add("stacks", stacks); return result; }
    }

    private record ActionChecks(Set<String> captainMine, Set<String> gathererMine, Set<String> builderMine,
                                Set<String> captainCraft, Set<String> gathererCraft, Set<String> builderCraft,
                                Set<String> captainTransfer, Set<String> gathererTransfer, List<Set<String>> places,
                                Set<String> builderBuilds, Set<String> builderCompletedBuilds,
                                Set<String> builderCompletedPlaces) {
        private ActionChecks() {
            this(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of(), Set.of(), Set.of(), Set.of());
        }
        private boolean one(Set<String> ids) { return ids.size() == 1; }
        private String buildId() { return builderBuilds.size() == 1 ? builderBuilds.iterator().next() : ""; }
        private boolean commonComplete() {
            return one(captainMine) && one(gathererMine) && one(builderMine)
                    && one(captainCraft) && one(gathererCraft) && one(builderCraft)
                    && one(captainTransfer) && one(gathererTransfer);
        }
        private boolean complete() {
            if (!commonComplete()) return false;
            if (buildMode()) {
                return builderBuilds.size() == 1 && builderCompletedBuilds.equals(builderBuilds)
                        && builderCompletedPlaces.isEmpty()
                        && places.stream().allMatch(Set::isEmpty);
            }
            return builderCompletedBuilds.isEmpty() && builderCompletedPlaces.size() == 3
                    && places.size() == 3 && places.stream().allMatch(this::one);
        }
        private String fingerprint() {
            return captainMine.size()+":"+gathererMine.size()+":"+builderMine.size()+":"
                    +captainCraft.size()+":"+gathererCraft.size()+":"+builderCraft.size()+":"
                    +captainTransfer.size()+":"+gathererTransfer.size()+":"
                    +places.stream().mapToInt(Set::size).boxed().toList()+":"
                    +builderBuilds.size()+":"+builderCompletedBuilds.size()+":"+builderCompletedPlaces.size();
        }
        private JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("complete", complete());
            result.addProperty("constructionMode", constructionMode());
            result.addProperty("buildId", buildId());
            result.addProperty("builderBuildCompletedIds", builderBuilds.toString());
            result.addProperty("builderAllCompletedBuildIds", builderCompletedBuilds.toString());
            result.addProperty("builderAllCompletedPlaceIds", builderCompletedPlaces.toString());
            result.addProperty("captainMineCompletedIds", captainMine.toString());
            result.addProperty("gathererMineCompletedIds", gathererMine.toString());
            result.addProperty("builderMineCompletedIds", builderMine.toString());
            result.addProperty("captainCraftCompletedIds", captainCraft.toString());
            result.addProperty("gathererCraftCompletedIds", gathererCraft.toString());
            result.addProperty("builderCraftCompletedIds", builderCraft.toString());
            result.addProperty("captainTransferCompletedIds", captainTransfer.toString());
            result.addProperty("gathererTransferCompletedIds", gathererTransfer.toString());
            JsonArray placeIds = new JsonArray();
            for (Set<String> ids : places) placeIds.add(ids.toString());
            result.add("builderPlaceCompletedIds", placeIds);
            return result;
        }
    }
}
