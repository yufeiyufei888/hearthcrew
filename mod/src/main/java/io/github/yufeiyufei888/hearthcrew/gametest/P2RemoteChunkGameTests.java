package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.CompanionChunks;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Development-only remote entity-ticking evidence. The remote line is a
 * bounded fixture; it is not evidence for unbounded loading or ordinary
 * survival exploration in a production world.
 */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2RemoteChunkGameTests {
    private static final int FLOOR_Y = 64;
    private static final BlockPos START = new BlockPos(328, FLOOR_Y + 1, 0);  // chunk 20 center
    private static final BlockPos FIRST_TARGET = new BlockPos(360, FLOOR_Y + 1, 0); // chunk 22 center
    private static final BlockPos FINAL_TARGET = new BlockPos(392, FLOOR_Y + 1, 0); // chunk 24 center

    private P2RemoteChunkGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 700, batch = "hearthcrew_p2_remote_chunks")
    public static void remoteTicketCarriesRealMovementAcrossChunkBoundaries(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildBoundedRemotePlatform(level);
        CrewWorldData data = new CrewWorldData();
        CompanionChunks chunks = new CompanionChunks(level.getServer(), data);
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(level);
        if (body == null) {
            chunks.close();
            helper.fail("could not construct remote companion fixture");
            return;
        }
        body.setRespawnEnabled(false);
        body.moveTo(START.getX() + 0.5, START.getY(), START.getZ() + 0.5, 0.0F, 0.0F);
        if (!level.noCollision(body) || !level.addFreshEntity(body)) {
            body.discard();
            chunks.close();
            helper.fail("remote companion fixture could not be placed safely");
            return;
        }
        if (!chunks.track(body)) {
            body.discard();
            chunks.close();
            helper.fail("remote companion could not obtain its bounded ticket");
            return;
        }
        assertPlayersRemote(level, START, FINAL_TARGET);
        boolean[] movementStarted = {false};
        helper.onEachTick(() -> {
            chunks.tick();
            if (!movementStarted[0]) return;
            if (!level.isPositionEntityTicking(body.blockPosition())) {
                throw new GameTestAssertException("body left the entity-ticking region: " + diagnostics(chunks, body));
            }
            if (activeTickets(chunks) > 1) {
                throw new GameTestAssertException("remote fixture exceeded one active ticket: " + diagnostics(chunks, body));
            }
            assertPlayersRemote(level, START, body.blockPosition());
        });
        helper.runAfterDelay(680, () -> cleanup(chunks, body));

        String firstId = "p2-remote-move-first-" + body.getUUID();
        String secondId = "p2-remote-move-second-" + body.getUUID();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!level.isPositionEntityTicking(body.blockPosition())) {
                        throw new GameTestAssertException("remote start chunk is not entity-ticking yet: " + diagnostics(chunks, body));
                    }
                    if (!level.hasChunkAt(FIRST_TARGET)) {
                        throw new GameTestAssertException("first remote target chunk is not loaded yet: " + diagnostics(chunks, body));
                    }
                })
                .thenExecute(() -> {
                    movementStarted[0] = true;
                    body.executor().submit(firstId, BodyOrder.move(FIRST_TARGET), ActionPriority.MISSION);
                })
                .thenWaitUntil(() -> {
                    var snapshot = body.executor().arbiter().snapshot(ActionId.of(firstId)).orElse(null);
                    if (snapshot == null) throw new GameTestAssertException("first remote MOVE was not recorded");
                    if (snapshot.state() == ActionState.FAILED || snapshot.state() == ActionState.STALE
                            || snapshot.state() == ActionState.EXPIRED) {
                        throw new GameTestAssertException("first remote MOVE failed: " + snapshot + ", " + diagnostics(chunks, body));
                    }
                    if (snapshot.state() == ActionState.COMPLETED) {
                        if (body.position().distanceToSqr(FIRST_TARGET.getX() + 0.5, FIRST_TARGET.getY(), FIRST_TARGET.getZ() + 0.5) > 1.25 * 1.25) {
                            throw new GameTestAssertException("first MOVE reported completion without physical arrival: " + body.position());
                        }
                        return;
                    }
                    throw new GameTestAssertException("waiting for first remote MOVE: " + snapshot + ", body=" + body.position());
                })
                .thenWaitUntil(() -> {
                    if (!level.isPositionEntityTicking(body.blockPosition())) {
                        throw new GameTestAssertException("body is not entity-ticking after first boundary: " + diagnostics(chunks, body));
                    }
                    if (!level.hasChunkAt(FINAL_TARGET)) {
                        throw new GameTestAssertException("waiting for final remote target chunk to be loaded after anchor migration: " + diagnostics(chunks, body));
                    }
                })
                .thenExecute(() -> body.executor().submit(secondId, BodyOrder.move(FINAL_TARGET), ActionPriority.MISSION))
                .thenWaitUntil(() -> {
                    var snapshot = body.executor().arbiter().snapshot(ActionId.of(secondId)).orElse(null);
                    if (snapshot == null) throw new GameTestAssertException("second remote MOVE was not recorded");
                    if (snapshot.state() == ActionState.FAILED || snapshot.state() == ActionState.STALE
                            || snapshot.state() == ActionState.EXPIRED) {
                        throw new GameTestAssertException("second remote MOVE failed: " + snapshot + ", " + diagnostics(chunks, body));
                    }
                    if (snapshot.state() != ActionState.COMPLETED) {
                        throw new GameTestAssertException("waiting for second remote MOVE: " + snapshot + ", body=" + body.position());
                    }
                    chunks.tick();
                    if (!level.isPositionEntityTicking(body.blockPosition())) {
                        throw new GameTestAssertException("final body position is not entity-ticking: " + diagnostics(chunks, body));
                    }
                    if (body.position().distanceToSqr(FINAL_TARGET.getX() + 0.5, FINAL_TARGET.getY(), FINAL_TARGET.getZ() + 0.5) > 1.25 * 1.25) {
                        throw new GameTestAssertException("second MOVE completed without physical arrival: " + body.position());
                    }
                    var anchor = data.chunkAnchors().get(body.companionId());
                    if (anchor == null || anchor.chunkX() != FINAL_TARGET.getX() >> 4
                            || !anchor.dimension().equals(Level.OVERWORLD.location().toString())) {
                        throw new GameTestAssertException("saved anchor did not follow remote body: " + data.chunkAnchors() + ", body=" + body.position());
                    }
                    var diagnostics = chunks.diagnostics();
                    if (!Integer.valueOf(1).equals(diagnostics.get("activeTickets"))) {
                        throw new GameTestAssertException("remote movement did not retain exactly one active ticket: " + diagnostics);
                    }
                    assertPlayersRemote(level, START, body.blockPosition());
                    BlockPos gameTestOrigin = helper.absolutePos(new BlockPos(0, 1, 0));
                    if (body.distanceToSqr(gameTestOrigin.getX() + 0.5, body.getY(), gameTestOrigin.getZ() + 0.5) < 256 * 256) {
                        throw new GameTestAssertException("remote body did not remain at least 256 blocks from the GameTest origin: " + body.position());
                    }
                })
                .thenExecute(() -> cleanup(chunks, body))
                .thenSucceed();
    }

    private static void buildBoundedRemotePlatform(ServerLevel level) {
        // This is only deterministic setup. No entity is moved by teleport after
        // submission; both traversals below use vanilla navigation and physics.
        for (int x = START.getX() - 2; x <= FINAL_TARGET.getX() + 2; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void assertPlayersRemote(ServerLevel level, BlockPos start, BlockPos target) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (distanceSqr(player, start) <= 128 * 128 || distanceSqr(player, target) <= 128 * 128) {
                throw new GameTestAssertException("remote fixture is too close to a player: " + player.getGameProfile().getName()
                        + " at " + player.position());
            }
        }
    }

    private static double distanceSqr(ServerPlayer player, BlockPos position) {
        return player.distanceToSqr(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
    }

    private static int activeTickets(CompanionChunks chunks) {
        Object value = chunks.diagnostics().get("activeTickets");
        return value instanceof Integer count ? count : Integer.MAX_VALUE;
    }

    private static String diagnostics(CompanionChunks chunks, CompanionEntity body) {
        return "bodyAlive=" + body.isAlive() + ", removed=" + body.isRemoved() + ", pos=" + body.position()
                + ", tick=" + body.tickCount + ", " + chunks.diagnostics();
    }

    private static void cleanup(CompanionChunks chunks, CompanionEntity body) {
        chunks.close();
        if (body.isAlive()) body.discard();
    }
}
