package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.runtime.CompanionChunks;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.lang.reflect.Constructor;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** P2 bounded companion ticket and saved-anchor gates. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2ChunkGameTests {
    private P2ChunkGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_chunks")
    public static void ticketCapacityIsBoundedAtThree(GameTestHelper helper) {
        CompanionChunks chunks = new CompanionChunks(helper.getLevel().getServer(), new CrewWorldData());
        CompanionEntity[] bodies = new CompanionEntity[4];
        for (int i = 0; i < bodies.length; i++) {
            bodies[i] = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1 + i, 1, 1));
            bodies[i].setRespawnEnabled(false);
        }
        try {
            for (int i = 0; i < 3; i++) {
                if (!chunks.track(bodies[i])) throw new GameTestAssertException("registered companion was not assigned a bounded ticket: " + i);
            }
            if (chunks.track(bodies[3])) throw new GameTestAssertException("fourth companion exceeded the ticket capacity");
            var diagnostics = chunks.diagnostics();
            if (!Integer.valueOf(3).equals(diagnostics.get("registered"))
                    || !Integer.valueOf(0).equals(diagnostics.get("activeTickets"))
                    || !"native_player_tickets".equals(diagnostics.get("bodyLoading"))) {
                throw new GameTestAssertException("native player ticket diagnostics were inconsistent: " + diagnostics);
            }
        } finally {
            chunks.close();
            discard(bodies);
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_chunks")
    public static void chunkAnchorPersistsAndMalformedDataFailsClosed(GameTestHelper helper) {
        UUID identity = UUID.randomUUID();
        CrewWorldData original = new CrewWorldData();
        CompanionChunks.Anchor expected = new CompanionChunks.Anchor(Level.OVERWORLD.location().toString(), 17, -23);
        if (!original.rememberChunkAnchor(identity, expected)) helper.fail("valid anchor was rejected");
        CrewWorldData restored = restore(original.save(new CompoundTag(), helper.getLevel().registryAccess()));
        if (restored.chunkRecoveryInvalid() || !expected.equals(restored.chunkAnchors().get(identity))) {
            helper.fail("valid chunk anchor did not survive saved-data round trip");
        }

        CompoundTag malformed = new CompoundTag();
        ListTag entries = new ListTag();
        CompoundTag missingCoordinate = new CompoundTag();
        missingCoordinate.putUUID("identity", UUID.randomUUID());
        missingCoordinate.putString("dimension", Level.OVERWORLD.location().toString());
        entries.add(missingCoordinate);
        malformed.put("chunkAnchors", entries);
        CrewWorldData invalid = restore(malformed);
        if (!invalid.chunkRecoveryInvalid() || !invalid.chunkAnchors().isEmpty()) {
            helper.fail("malformed anchor data was not rejected fail-closed");
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_chunks")
    public static void movingCompanionMovesSavedAnchorAndKeepsOneActiveTicket(GameTestHelper helper) {
        CrewWorldData data = new CrewWorldData();
        CompanionChunks chunks = new CompanionChunks(helper.getLevel().getServer(), data);
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        body.setRespawnEnabled(false);
        try {
            if (!chunks.track(body)) helper.fail("initial companion ticket was not registered");
            int oldChunkX = data.chunkAnchors().get(body.companionId()).chunkX();
            BlockPos fixtureDestination = helper.absolutePos(new BlockPos(32, 1, 1));
            // This is fixture placement to exercise ticket migration, not movement evidence.
            body.moveTo(fixtureDestination.getX() + 0.5, fixtureDestination.getY(), fixtureDestination.getZ() + 0.5, 0.0F, 0.0F);
            chunks.tick();
            int newChunkX = data.chunkAnchors().get(body.companionId()).chunkX();
            if (oldChunkX == newChunkX) helper.fail("companion anchor did not follow its new chunk");
            var diagnostics = chunks.diagnostics();
            if (!Integer.valueOf(0).equals(diagnostics.get("activeTickets"))
                    || !"native_player_tickets".equals(diagnostics.get("bodyLoading")))
                helper.fail("moving anchor did not retain native player loading state");
        } finally {
            chunks.close();
            body.discard();
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_p2_chunks")
    public static void closeReleasesActiveTickets(GameTestHelper helper) {
        CompanionChunks chunks = new CompanionChunks(helper.getLevel().getServer(), new CrewWorldData());
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        body.setRespawnEnabled(false);
        try {
            if (!chunks.track(body)) helper.fail("companion ticket was not registered");
            chunks.close();
            var diagnostics = chunks.diagnostics();
            if (!Boolean.TRUE.equals(diagnostics.get("closed")) || !Integer.valueOf(0).equals(diagnostics.get("activeTickets"))) {
                helper.fail("close did not release active companion tickets: " + diagnostics);
            }
        } finally {
            body.discard();
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 160, batch = "hearthcrew_p2_chunks")
    public static void missingBodyEventuallyReleasesTicket(GameTestHelper helper) {
        CrewWorldData data = new CrewWorldData();
        CompanionChunks chunks = new CompanionChunks(helper.getLevel().getServer(), data);
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 1));
        body.setRespawnEnabled(false);
        UUID identity = body.companionId();
        if (!chunks.track(body)) helper.fail("companion ticket was not registered");
        body.discard();
        // Keep a failing timeout from leaking the isolated ticket into later batches.
        helper.runAfterDelay(120, chunks::close);
        int[] ticks = {0};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    chunks.tick();
                    ticks[0]++;
                    if (ticks[0] < 45) throw new GameTestAssertException("waiting for bounded missing-body release");
                    var diagnostics = chunks.diagnostics();
                    if (!Integer.valueOf(0).equals(diagnostics.get("activeTickets"))) {
                        throw new GameTestAssertException("missing body ticket was not released: " + diagnostics);
                    }
                    @SuppressWarnings("unchecked")
                    var unavailable = (java.util.List<String>) diagnostics.get("unavailable");
                    if (!unavailable.contains(identity.toString())) throw new GameTestAssertException("missing body was not marked unavailable: " + diagnostics);
                })
                .thenExecute(chunks::close)
                .thenSucceed();
    }

    private static void discard(CompanionEntity[] bodies) {
        for (CompanionEntity body : bodies) if (body != null && body.isAlive()) body.discard();
    }

    private static CrewWorldData restore(CompoundTag tag) {
        try {
            Constructor<CrewWorldData> constructor = CrewWorldData.class.getDeclaredConstructor(CompoundTag.class);
            constructor.setAccessible(true);
            return constructor.newInstance(tag);
        } catch (ReflectiveOperationException exception) {
            throw new GameTestAssertException("could not invoke CrewWorldData saved-data parser: " + exception.getClass().getSimpleName());
        }
    }
}
