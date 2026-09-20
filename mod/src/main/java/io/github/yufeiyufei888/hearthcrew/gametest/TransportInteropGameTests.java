package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.WorldEvents;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlled Mod-to-bridge transport fixture. It is deliberately absent from
 * the registry unless the live interop probe opts in with a system property.
 *
 * <p>This is a separate test namespace so an interop run can load only this
 * fixture. Its forced tickets are test scaffolding for the destination
 * platform; they do not claim that production remote exploration has a
 * complete chunk-ticket policy.</p>
 */
@GameTestHolder(TransportInteropGameTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public final class TransportInteropGameTests {
    public static final String NAMESPACE = "hearthcrewinterop";
    private static final String RUN_ID_PROPERTY = "hearthcrew.interopRunId";
    private static final Logger LOGGER = LoggerFactory.getLogger(TransportInteropGameTests.class);

    private TransportInteropGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> generate() {
        if (!Boolean.getBoolean("hearthcrew.interopTest")) return List.of();
        String runId = System.getProperty(RUN_ID_PROPERTY, "").trim();
        if (!runId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalStateException("hearthcrew.interopTest requires a safe " + RUN_ID_PROPERTY);
        }
        return List.of(new TestFunction(
                "hearthcrew_transport_interop",
                "transportinteropfixture",
                NAMESPACE + ":p0_empty",
                // The GameTest server intentionally runs ticks without wall-clock pacing.
                // Keep the real transport probe alive long enough for Gradle startup and
                // reconnect checks; completion still requires the final WAIT receipt below.
                120_000,
                0L,
                true,
                helper -> InteropFixture.run(helper, runId)));
    }

    /** Kept outside the holder class so the annotated fixture is only reached by the generator. */
    private static final class InteropFixture {
        @GameTest(template = "p0_empty", templateNamespace = NAMESPACE,
                timeoutTicks = 120_000, batch = "hearthcrew_transport_interop")
        public static void run(GameTestHelper helper) {
            run(helper, System.getProperty(RUN_ID_PROPERTY, "fixture"));
        }

        private static void run(GameTestHelper helper, String runId) {
            ServerLevel level = helper.getLevel();
            // The template is 5x5, while the bridge target is six blocks beyond
            // the body's initial block. Build a complete solid 11x5 runway so
            // every navigation node and the target have real collision support.
            // GameTest encases a structure with BARRIER blocks. The runway is
            // intentionally longer than the template, so open only its east
            // wall in this fixture; otherwise vanilla pathfinding stops at the
            // template boundary even though the actual target platform exists.
            for (int x = 0; x <= 10; x++) {
                for (int z = 0; z <= 4; z++) {
                    BlockPos floor = helper.absolutePos(new BlockPos(x, 0, z));
                    level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 1; y <= 3; y++) {
                        level.setBlock(floor.above(y), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            BlockPos start = new BlockPos(1, 1, 2);
            BlockPos target = new BlockPos(7, 1, 2);
            BlockPos absoluteStart = helper.absolutePos(start);
            BlockPos absoluteTarget = helper.absolutePos(target);
            openRunwayBarrier(level, absoluteStart);
            Set<ChunkPos> ticketedChunks = fixtureChunks(helper, 0, 10, 0, 4);
            for (ChunkPos chunk : ticketedChunks) {
                // Official ServerChunkCache ticket API. forceTicks is required:
                // a loaded chunk alone is insufficient for a remote Mob entity.
                level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk, true);
            }

            CompanionEntity[] bodyRef = {null};
            boolean[] ticketsReleased = {false};
            boolean[] terminalDiagnosticLogged = {false};
            helper.startSequence()
                    // Do not publish the named body to the bridge until both
                    // endpoints and the whole runway are entity-ticking. This
                    // prevents the probe from selecting a half-ready fixture.
                    .thenWaitUntil(() -> {
                        if (!allEntityTicking(level, ticketedChunks)) throw new GameTestAssertException("interop platform is not entity-ticking yet");
                    })
                    .thenExecute(() -> {
                        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, start);
                        body.setCustomName(net.minecraft.network.chat.Component.literal("interop_fixture_" + runId));
                        body.setRespawnEnabled(false);
                        body.inventory().clearContent();
                        bodyRef[0] = body;
                    })
                    .thenWaitUntil(() -> {
                        CompanionEntity body = bodyRef[0];
                        if (body == null) throw new GameTestAssertException("interop fixture body not created yet");
                        ActionState move = stateOf(body, "interop-move");
                        ActionState finish = stateOf(body, "interop-finish");
                        if (!body.isAlive() || move == ActionState.FAILED || finish == ActionState.FAILED) {
                            if (!terminalDiagnosticLogged[0]) {
                                terminalDiagnosticLogged[0] = true;
                                LOGGER.error("{}; {}", diagnostics(level, body, absoluteStart, absoluteTarget),
                                        terminalDiagnostics(level, body, absoluteTarget));
                            }
                            releaseTickets(level, ticketedChunks, ticketsReleased);
                            throw new GameTestAssertException(diagnostics(level, body, absoluteStart, absoluteTarget));
                        }
                        boolean atTarget = body.position().distanceToSqr(absoluteTarget.getX() + 0.5,
                                absoluteTarget.getY(), absoluteTarget.getZ() + 0.5) <= 1.25 * 1.25
                                && Math.abs(body.getY() - absoluteTarget.getY()) <= 1.25;
                        if (move == ActionState.COMPLETED && finish == ActionState.COMPLETED && atTarget
                                && !WorldEvents.controllerConnected()) return;
                        if ((level.getGameTime() % 2000L) == 0L) {
                            LOGGER.warn("interop fixture still waiting: {}", diagnostics(level, body, absoluteStart, absoluteTarget));
                        }
                        throw new GameTestAssertException(diagnostics(level, body, absoluteStart, absoluteTarget)
                                + ", controllerConnected=" + WorldEvents.controllerConnected());
                    })
                    .thenExecute(() -> {
                        releaseTickets(level, ticketedChunks, ticketsReleased);
                    })
                    .thenSucceed();
        }

        private static Set<ChunkPos> fixtureChunks(GameTestHelper helper, int minX, int maxX, int minZ, int maxZ) {
            Set<ChunkPos> result = new HashSet<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) result.add(new ChunkPos(helper.absolutePos(new BlockPos(x, 1, z))));
            }
            return result;
        }

        private static boolean allEntityTicking(ServerLevel level, Set<ChunkPos> chunks) {
            for (ChunkPos chunk : chunks) {
                BlockPos probe = chunk.getMiddleBlockPosition(1);
                if (!level.isPositionEntityTicking(probe)) return false;
            }
            return true;
        }

        private static void openRunwayBarrier(ServerLevel level, BlockPos absoluteStart) {
            int wallX = absoluteStart.getX() + 4;
            for (int y = -1; y <= 6; y++) {
                for (int z = -3; z <= 3; z++) {
                    level.setBlock(new BlockPos(wallX, absoluteStart.getY() + y, absoluteStart.getZ() + z),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        private static void releaseTickets(ServerLevel level, Set<ChunkPos> chunks, boolean[] released) {
            if (released[0]) return;
            for (ChunkPos chunk : chunks) level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk, true);
            released[0] = true;
        }

        private static ActionState stateOf(CompanionEntity body, String id) {
            return body.executor().arbiter().snapshot(ActionId.of(id)).map(snapshot -> snapshot.state()).orElse(null);
        }

        private static String diagnostics(ServerLevel level, CompanionEntity body, BlockPos start, BlockPos target) {
            if (body == null) return "interop fixture body missing; serverTick=" + level.getGameTime();
            return "interop fixture failure: serverTick=" + level.getGameTime()
                    + ", bodyTick=" + body.tickCount
                    + ", start=" + start + ", target=" + target
                    + ", bodyPos=" + body.position()
                    + ", bodyAlive=" + body.isAlive() + ", removed=" + body.isRemoved()
                    + ", move=" + actionDiagnostics(body, "interop-move")
                    + ", finish=" + actionDiagnostics(body, "interop-finish");
        }

        private static String terminalDiagnostics(ServerLevel level, CompanionEntity body, BlockPos target) {
            BlockPos feet = body.blockPosition().below();
            int dx = Integer.compare(target.getX(), body.blockPosition().getX());
            int dz = Integer.compare(target.getZ(), body.blockPosition().getZ());
            BlockPos front = body.blockPosition().offset(dx, -1, dz);
            return "terminal body={alive=" + body.isAlive() + ",removed=" + body.isRemoved()
                    + ",reason=" + body.getRemovalReason() + ",health=" + body.getHealth()
                    + ",pos=" + body.position() + ",tick=" + body.tickCount + "}"
                    + ", serverTick=" + level.getGameTime()
                    + ", feet=" + feet + ":" + level.getBlockState(feet)
                    + ", frontFloor=" + front + ":" + level.getBlockState(front);
        }

        private static String actionDiagnostics(CompanionEntity body, String id) {
            return body.executor().arbiter().snapshot(ActionId.of(id))
                    .map(snapshot -> snapshot.state() + ":" + snapshot.message()).orElse("missing");
        }
    }
}
