package io.github.yufeiyufei888.hearthcrew.client;

import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
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
 * Controlled real-client model fixture.  It is opt-in, only accepts the new
 * P1Model save, and never submits an AI action.  The bridge/model supplies all
 * MINE, CRAFT, and TRANSFER requests; this class only creates the clean scene
 * and records observed postconditions.
 */
@EventBusSubscriber(modid = HearthCrew.ID, value = Dist.CLIENT)
public final class DevelopmentModelFixture {
    public static final String SAVE_NAME = "P1Model";
    public static final String ENABLE_PROPERTY = "hearthcrew.modelTest";
    public static final String RUN_ID_PROPERTY = "hearthcrew.modelRunId";
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,48}");
    private static final ResourceLocation OAK_PLANKS_RECIPE = ResourceLocation.parse("minecraft:oak_planks");
    private static final Logger LOGGER = LoggerFactory.getLogger(DevelopmentModelFixture.class);
    private static final GsonBuilder JSON = new GsonBuilder().setPrettyPrinting();
    private static boolean setupRequested;
    private static volatile boolean fixtureReady;
    private static boolean invalidRunReported;
    private static volatile boolean monitorQueued;
    private static String activeRunId;
    private static String lastReportStatus;
    private static String lastReportFingerprint;
    private static long lastReportTick = Long.MIN_VALUE;
    private static volatile String statusForClient;
    private static volatile String terminalStatus;
    private static BlockPos oakPosition;
    private static UUID fixtureWorldId;
    private static UUIDPair bodies;
    private static boolean directoryReported;
    private static boolean readyScreenshotTaken;
    private static boolean terminalScreenshotTaken;
    private static int visibleTicks;

    private DevelopmentModelFixture() {}

    public static String saveName(String runId) {
        if (!SAFE_RUN_ID.matcher(runId).matches()) throw new IllegalArgumentException("unsafe fixture run ID");
        return SAVE_NAME + "-" + runId;
    }

    /** Client ticks continue while the window is unfocused; no screen/focus gate is used. */
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) return;
        String runId = System.getProperty(RUN_ID_PROPERTY, "").trim();
        if (!SAFE_RUN_ID.matcher(runId).matches()) {
            if (!invalidRunReported) {
                invalidRunReported = true;
                LOGGER.error("P1 model fixture requires -D{}=<1..48 safe run id>", RUN_ID_PROPERTY);
            }
            return;
        }
        Path expectedDirectory;
        try {
            String expected = System.getProperty("hearthcrew.fixtureDirectory", "").trim();
            if (expected.isEmpty()) throw new IllegalArgumentException("hearthcrew.fixtureDirectory is missing");
            expectedDirectory = Path.of(expected).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            if (!directoryReported) {
                directoryReported = true;
                LOGGER.error("P1 model fixture refuses to run without a valid -Dhearthcrew.fixtureDirectory", error);
            }
            return;
        }
        Path actualDirectory = client.gameDirectory.toPath().toAbsolutePath().normalize();
        Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!actualDirectory.equals(expectedDirectory)
                || !save.equals(expectedDirectory.resolve("saves").resolve(saveName(runId)).normalize())) return;
        if (activeRunId != null && !activeRunId.equals(runId)) {
            LOGGER.error("P1 model fixture runId changed while client is attached; refusing to mix runs (old={}, new={})", activeRunId, runId);
            return;
        }
        activeRunId = runId;
        if (fixtureReady && client.screen == null && visibleTicks < 20) visibleTicks++;
        if (fixtureReady && client.screen == null && visibleTicks >= 20 && !readyScreenshotTaken) {
            readyScreenshotTaken = true;
            Screenshot.grab(client.gameDirectory, "hearthcrew-p1-model-ready-" + runId + ".png",
                    client.getMainRenderTarget(), message -> LOGGER.info("P1 model fixture READY screenshot: {}", message));
        }
        if (terminalStatus != null) {
            if (client.screen == null && visibleTicks >= 20 && !terminalScreenshotTaken) {
                terminalScreenshotTaken = true;
                Screenshot.grab(client.gameDirectory, "hearthcrew-p1-model-" + terminalStatus.toLowerCase() + "-" + runId + ".png",
                        client.getMainRenderTarget(), message -> LOGGER.info("P1 model fixture terminal screenshot: {}", message));
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
                finally { monitorQueued = false; }
            });
        }
    }

    private static void setup(MinecraftServer server, UUID playerId, String runId) {
        ServerLevel level = server.overworld();
        Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path fixtureFile = save.resolve("hearthcrew-p1-model-fixture-" + runId + ".json");
        if (Files.exists(fixtureFile)) {
            LOGGER.error("P1 model fixture metadata already exists; use a fresh P1Model save and runId: {}", fixtureFile);
            // Reject the new launch without overwriting a previous PASS/FAIL.
            terminalStatus = "FAIL";
            statusForClient = "FAIL";
            terminalScreenshotTaken = true;
            JsonObject rejection = new JsonObject();
            rejection.addProperty("status", "REJECTED_EXISTING_EVIDENCE");
            rejection.addProperty("runId", runId);
            rejection.addProperty("reason", "fixture metadata already exists; fresh save/runId required");
            try {
                Files.writeString(save.resolve("hearthcrew-p1-startup-rejected-" + UUID.randomUUID() + ".json"),
                        JSON.create().toJson(rejection), java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException error) { LOGGER.error("Could not write independent startup rejection record", error); }
            return;
        }
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            publishTerminalFailure(save, server.overworld(), runId, "fixture owner player was unavailable during setup");
            return;
        }

        // A small ordinary-survival platform, with headroom and one real oak
        // log.  No item, action, invulnerability, or game-rule shortcut is
        // supplied to either body.
        BlockPos origin = new BlockPos(0, 100, 0);
        for (int x = -6; x <= 6; x++) for (int z = -5; z <= 5; z++) {
            level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 1; y <= 5; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        BlockPos log = origin.offset(3, 1, 0);
        level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);
        for (int y = 2; y <= 6; y++) level.setBlock(log.above(y - 1), Blocks.AIR.defaultBlockState(), 3);
        player.teleportTo(level, 0.5, 101, 4.5, 180, 8);

        // The recipient waits away from both the oak drops and the worker's
        // approach path. Accidental competing pickup is a separate team test;
        // this fixture measures one worker's pipeline and process recovery.
        CompanionEntity worker = createBody(level, playerId, "P1_WORKER_" + runId, -5.0, 101, -3.5);
        CompanionEntity recipient = createBody(level, playerId, "P1_RECIPIENT_" + runId, -3.5, 101, 3.5);
        if (worker == null || recipient == null) {
            LOGGER.error("P1 model fixture could not create both empty bodies");
            publishTerminalFailure(save, level, runId, "fixture could not create both empty bodies");
            return;
        }
        if (!level.addFreshEntity(worker) || !level.addFreshEntity(recipient)) {
            LOGGER.error("P1 model fixture could not add both empty bodies to the server level");
            publishTerminalFailure(save, level, runId, "fixture could not add both empty bodies to the server level");
            return;
        }
        oakPosition = log.immutable();
        bodies = new UUIDPair(worker.companionId(), worker.getUUID(), recipient.companionId(), recipient.getUUID());
        fixtureWorldId = CrewWorldData.get(server).worldId();
        JsonObject metadata = new JsonObject();
        metadata.addProperty("evidence", "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE");
        metadata.addProperty("status", "READY"); metadata.addProperty("saveName", saveName(runId));
        metadata.addProperty("runId", runId); metadata.addProperty("worldId", fixtureWorldId.toString());
        metadata.addProperty("serverTick", level.getGameTime()); metadata.add("oakPosition", position(log));
        metadata.add("worker", bodyMetadata(worker)); metadata.add("recipient", bodyMetadata(recipient));
        metadata.addProperty("initialInventoryEmpty", inventoryEmpty(worker) && inventoryEmpty(recipient));
        try {
            Files.writeString(fixtureFile, JSON.create().toJson(metadata), java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            fixtureReady = true;
            LOGGER.info("P1 model fixture READY: runId={}, worldId={}, workerLogical={}, workerEntity={}, recipientLogical={}, recipientEntity={}, oak={}",
                    runId, fixtureWorldId, worker.companionId(), worker.getUUID(), recipient.companionId(), recipient.getUUID(), log);
        } catch (IOException error) {
            LOGGER.error("P1 model fixture metadata could not be written", error);
            publishTerminalFailure(save, level, runId, "fixture metadata write failed: " + error.getClass().getSimpleName());
        }
    }

    private static CompanionEntity createBody(ServerLevel level, UUID owner, String name, double x, double y, double z) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(level);
        if (body == null) return null;
        body.setOwner(owner); body.setCustomName(Component.literal(name)); body.setCustomNameVisible(true);
        body.moveTo(x, y, z, 0, 0); body.setYHeadRot(0); body.yBodyRot = 0;
        body.inventory().clearContent(); body.setInvulnerable(false); body.setRespawnEnabled(true);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND))
            body.setItemSlot(slot, ItemStack.EMPTY);
        return body;
    }

    private static void monitor(MinecraftServer server, String runId) {
        if (terminalStatus != null) return;
        ServerLevel level = server.overworld();
        Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        UUID currentWorldId = CrewWorldData.get(server).worldId();
        CompanionEntity worker = bodies == null ? null : findBody(server, level, bodies.workerLogical());
        CompanionEntity recipient = bodies == null ? null : findBody(server, level, bodies.recipientLogical());
        String failure = "";
        if (fixtureWorldId == null || !fixtureWorldId.equals(currentWorldId)) {
            failure = "worldId changed during the controlled run";
        } else if (bodies == null || worker == null || recipient == null || !worker.isAlive() || !recipient.isAlive()) {
            failure = "fixture body missing or dead";
        } else if (!identityMatches(worker, bodies.workerLogical(), bodies.workerEntity())
                || !identityMatches(recipient, bodies.recipientLogical(), bodies.recipientEntity())) {
            failure = "companion logical/entity identity changed during the controlled run";
        }
        boolean oakAir = oakPosition != null && level.getBlockState(oakPosition).isAir();
        int workerWood = worker == null ? 0 : countItems(worker, Items.OAK_LOG, Items.OAK_PLANKS);
        int recipientPlanks = recipient == null ? 0 : countItems(recipient, Items.OAK_PLANKS);
        boolean mineCompleted = hasCompletedMine(worker);
        boolean craftCompleted = hasCompletedCraft(worker);
        boolean transferCompleted = hasCompletedTransfer(worker);
        String actionFailure = hasRelevantFailure(worker, recipient);
        if (failure.isEmpty() && actionFailure != null) failure = actionFailure;
        if (failure.isEmpty() && recipientPlanks > 4) failure = "recipient received more than the exact four expected oak planks";
        boolean passed = failure.isEmpty() && worker != null && recipient != null && oakAir && workerWood == 0 && recipientPlanks == 4
                && mineCompleted && craftCompleted && transferCompleted;
        if (!failure.isEmpty()) {
            publishTerminalFailure(save, level, runId, failure, worker, recipient, oakAir, workerWood, recipientPlanks,
                    mineCompleted, craftCompleted, transferCompleted);
            return;
        }
        if (passed) {
            publishTerminal(save, level, runId, "", worker, recipient, oakAir, workerWood, recipientPlanks,
                    mineCompleted, craftCompleted, transferCompleted, "PASS");
            return;
        }
        String status = "PENDING";
        String fingerprint = status + '|' + oakAir + '|' + workerWood + '|' + recipientPlanks + '|'
                + mineCompleted + '|' + craftCompleted + '|' + transferCompleted;
        long gameTick = level.getGameTime();
        boolean reportPersisted = Objects.equals(lastReportFingerprint, fingerprint) && gameTick - lastReportTick < 20;
        if (!reportPersisted) {
            // A report reader may briefly hold the destination while this
            // tick tries to replace it.  Keep the observed update pending and
            // retry on the next monitor tick; never acknowledge a failed
            // write by advancing the durable fingerprint/tick.
            reportPersisted = writeReport(save, runId, level, worker, recipient, status, failure, oakAir, workerWood, recipientPlanks,
                    mineCompleted, craftCompleted, transferCompleted);
            if (reportPersisted) {
                lastReportFingerprint = fingerprint;
                lastReportTick = gameTick;
            }
        }
        if (reportPersisted) {
            statusForClient = status;
            if (!Objects.equals(lastReportStatus, status)) {
                lastReportStatus = status;
                LOGGER.info("P1 model fixture {}: runId={}, serverTick={}, oakAir={}, recipientOakPlanks={}, workerWood={}, mine={}, craft={}, transfer={}",
                        status, runId, level.getGameTime(), oakAir, recipientPlanks, workerWood, mineCompleted, craftCompleted, transferCompleted);
            }
        }
    }

    private static CompanionEntity findBody(MinecraftServer server, ServerLevel expectedLevel, UUID logicalId) {
        var matches = CrewWorldData.liveCompanions(server).stream()
                .filter(body -> body.level() == expectedLevel && logicalId.equals(body.companionId())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean identityMatches(CompanionEntity body, UUID logicalId, UUID entityId) {
        return body != null && body.isAlive() && logicalId.equals(body.companionId()) && entityId.equals(body.getUUID());
    }

    private static boolean hasCompletedMine(CompanionEntity body) {
        if (body == null) return false;
        for (var receipt : body.executor().arbiter().journal())
            if (receipt.state() == ActionState.COMPLETED && receipt.payload() instanceof BodyOrder order
                    // MINE targets exactly one BlockPos; its generic count
                    // field is unused by the executor and may be omitted.
                    && order.kind() == BodyOrder.Kind.MINE
                    && oakPosition != null && oakPosition.equals(order.position())) return true;
        return false;
    }

    private static boolean hasCompletedCraft(CompanionEntity body) {
        if (body == null) return false;
        for (var receipt : body.executor().arbiter().journal())
            if (receipt.state() == ActionState.COMPLETED && receipt.payload() instanceof BodyOrder order
                    && order.kind() == BodyOrder.Kind.CRAFT && order.count() == 1
                    && OAK_PLANKS_RECIPE.equals(order.resource())) return true;
        return false;
    }

    private static boolean hasCompletedTransfer(CompanionEntity body) {
        if (body == null || bodies == null) return false;
        for (var receipt : body.executor().arbiter().journal())
            if (receipt.state() == ActionState.COMPLETED && receipt.payload() instanceof BodyOrder order
                    && order.kind() == BodyOrder.Kind.TRANSFER && order.count() == 4
                    && bodies.recipientEntity().equals(order.target())
                    && OAK_PLANKS_RECIPE.equals(order.resource())) return true;
        return false;
    }

    private static String hasRelevantFailure(CompanionEntity... bodiesToCheck) {
        for (CompanionEntity body : bodiesToCheck) {
            if (body == null) continue;
            for (var receipt : body.executor().arbiter().journal()) {
                if (receipt.state() == ActionState.FAILED || receipt.state() == ActionState.RECONCILE_REQUIRED) {
                    return "a real action receipt is " + receipt.state() + ": " + receipt.message();
                }
            }
        }
        return null;
    }

    private static void publishTerminalFailure(Path save, ServerLevel level, String runId, String failure) {
        publishTerminalFailure(save, level, runId, failure, null, null, false, 0, 0, false, false, false);
    }

    private static void publishTerminalFailure(Path save, ServerLevel level, String runId, String failure,
                                               CompanionEntity worker, CompanionEntity recipient, boolean oakAir,
                                               int workerWood, int recipientPlanks, boolean mine, boolean craft,
                                               boolean transfer) {
        publishTerminal(save, level, runId, failure, worker, recipient, oakAir, workerWood, recipientPlanks,
                mine, craft, transfer, "FAIL");
    }

    private static void publishTerminal(Path save, ServerLevel level, String runId, String failure,
                                        CompanionEntity worker, CompanionEntity recipient, boolean oakAir,
                                        int workerWood, int recipientPlanks, boolean mine, boolean craft,
                                        boolean transfer, String status) {
        if (terminalStatus != null) return;
        // Terminal state is committed only after the report is durably
        // replaced.  A transient reader/lock failure leaves terminalStatus
        // unset so monitor() retries on the next server tick.
        if (!writeReport(save, runId, level, worker, recipient, status, failure, oakAir, workerWood, recipientPlanks,
                mine, craft, transfer)) return;
        terminalStatus = status;
        statusForClient = status;
        lastReportStatus = status;
        lastReportFingerprint = status + '|' + failure + '|' + oakAir + '|' + workerWood + '|' + recipientPlanks
                + '|' + mine + '|' + craft + '|' + transfer;
        lastReportTick = level.getGameTime();
        LOGGER.info("P1 model fixture {}: runId={}, serverTick={}, reason={}", status, runId, level.getGameTime(), failure);
    }

    private static int countItems(CompanionEntity body, Item... items) {
        int count = 0;
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
            ItemStack stack = body.inventory().getItem(slot);
            for (Item item : items) if (stack.is(item)) { count += stack.getCount(); break; }
        }
        return count;
    }

    private static boolean inventoryEmpty(CompanionEntity body) {
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) if (!body.inventory().getItem(slot).isEmpty()) return false;
        return true;
    }

    private static JsonObject bodyMetadata(CompanionEntity body) {
        JsonObject result = new JsonObject(); result.addProperty("logicalUuid", body.companionId().toString());
        result.addProperty("entityUuid", body.getUUID().toString()); result.addProperty("name", body.getDisplayName().getString());
        result.addProperty("initialInventoryEmpty", inventoryEmpty(body)); return result;
    }

    private static JsonObject position(BlockPos position) {
        JsonObject result = new JsonObject(); result.addProperty("x", position.getX()); result.addProperty("y", position.getY()); result.addProperty("z", position.getZ()); return result;
    }

    private static boolean writeReport(Path save, String runId, ServerLevel level, CompanionEntity worker, CompanionEntity recipient,
                                    String status, String failure, boolean oakAir, int workerWood, int recipientPlanks,
                                    boolean mine, boolean craft, boolean transfer) {
        JsonObject report = new JsonObject(); report.addProperty("evidence", "CONTROLLED_REAL_CLIENT_MODEL_FIXTURE");
        report.addProperty("status", status); report.addProperty("runId", runId); report.addProperty("saveName", saveName(runId));
        report.addProperty("worldId", CrewWorldData.get(level.getServer()).worldId().toString());
        if (fixtureWorldId != null) report.addProperty("expectedWorldId", fixtureWorldId.toString());
        report.addProperty("serverTick", level.getGameTime());
        report.addProperty("oakBlockAir", oakAir); report.addProperty("recipient4OakPlanks", recipientPlanks == 4);
        report.addProperty("recipientOakPlanks", recipientPlanks); report.addProperty("worker0WoodOrPlanks", workerWood == 0);
        report.addProperty("workerWoodOrPlanks", workerWood); report.addProperty("mineCompleted", mine);
        report.addProperty("craftCompleted", craft); report.addProperty("transferCompleted", transfer);
        report.addProperty("failure", failure); if (bodies != null) {
            report.addProperty("workerLogicalUuid", bodies.workerLogical.toString()); report.addProperty("workerEntityUuid", bodies.workerEntity.toString());
            report.addProperty("recipientLogicalUuid", bodies.recipientLogical.toString()); report.addProperty("recipientEntityUuid", bodies.recipientEntity.toString());
        }
        if (worker != null) {
            report.addProperty("workerAlive", worker.isAlive()); report.addProperty("workerDimension", worker.level().dimension().location().toString());
            report.addProperty("observedWorkerLogicalUuid", worker.companionId().toString()); report.addProperty("observedWorkerEntityUuid", worker.getUUID().toString());
        }
        if (recipient != null) {
            report.addProperty("recipientAlive", recipient.isAlive()); report.addProperty("recipientDimension", recipient.level().dimension().location().toString());
            report.addProperty("observedRecipientLogicalUuid", recipient.companionId().toString()); report.addProperty("observedRecipientEntityUuid", recipient.getUUID().toString());
        }
        Path destination = save.resolve("hearthcrew-p1-model-report-" + runId + ".json");
        Path temporary = save.resolve("hearthcrew-p1-model-report-" + runId + ".json.tmp");
        try {
            Files.writeString(temporary, JSON.create().toJson(report), java.nio.charset.StandardCharsets.UTF_8);
            try { Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
        catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
            LOGGER.error("P1 model fixture report could not be written; will retry on the next server tick", error);
            return false;
        }
    }

    private record UUIDPair(java.util.UUID workerLogical, java.util.UUID workerEntity,
                            java.util.UUID recipientLogical, java.util.UUID recipientEntity) {}
}
