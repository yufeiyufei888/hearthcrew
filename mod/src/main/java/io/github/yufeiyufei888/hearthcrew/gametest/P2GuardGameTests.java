package io.github.yufeiyufei888.hearthcrew.gametest;

import com.mojang.authlib.GameProfile;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ReceiptDecision;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** P2 local protection gates: owner-only proximity, one executor, and safe placement. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2GuardGameTests {
    private static final TicketType<String> COLD_GUARD_TICKET =
            TicketType.create("hearthcrew_guard_cold_fixture", Comparator.<String>naturalOrder());

    private P2GuardGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 500, batch = "hearthcrew_p2_guard")
    public static void ownerDamageStartsGuardAttacksAndResumesMine(GameTestHelper helper) {
        floor(helper);
        helper.setNight();
        ServerPlayer owner = owner(helper, new BlockPos(1, 1, 3));
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.setOwner(owner.getUUID());
        body.inventory().setItem(0, new ItemStack(Items.WOODEN_AXE, 1));
        BlockPos log = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_LOG);
        String mineId = "p2-guard-resume-mine-" + body.getUUID();
        helper.runAfterDelay(480, () -> retire(owner));

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        zombie.setNoAi(true); // The hit and the companion attack remain real; AI wandering is removed from the fixture.
        zombie.setHealth(1.0F);
        long[] damageTick = {-1L};
        helper.startSequence()
                .thenExecuteAfter(65, () -> body.executor().submit(mineId, BodyOrder.mine(log), ActionPriority.MISSION))
                .thenWaitUntil(() -> {
                    var mine = body.executor().arbiter().snapshot(ActionId.of(mineId)).orElse(null);
                    if (mine != null && mine.state() == ActionState.RUNNING && !helper.getLevel().getBlockState(log).isAir()) {
                        float before = owner.getHealth();
                        if (!zombie.doHurtTarget(owner)) helper.fail("zombie did not perform the owner damage action");
                        if (!(owner.getHealth() < before)) helper.fail("owner health did not decrease from hostile mob damage");
                        damageTick[0] = helper.getLevel().getGameTime();
                        return;
                    }
                    if (mine != null && mine.state().terminal()) {
                        throw new GameTestAssertException("MINE completed before the owner hit: " + mine.state() + ":" + mine.message());
                    }
                    throw new GameTestAssertException("waiting for a running slow MINE before the owner hit");
                })
                .thenWaitUntil(() -> {
                    var active = body.executor().arbiter().activeSnapshot();
                    if (active.isPresent() && active.get().payload().kind() == BodyOrder.Kind.GUARD) {
                        if (helper.getLevel().getGameTime() - damageTick[0] > 20) {
                            throw new GameTestAssertException("GUARD was accepted too late: " + active.get());
                        }
                        return;
                    }
                    if (helper.getLevel().getGameTime() - damageTick[0] > 20) {
                        throw new GameTestAssertException("owner damage did not create a GUARD lease within 20 ticks");
                    }
                    throw new GameTestAssertException("waiting for GUARD lease");
                })
                .thenWaitUntil(() -> {
                    if (zombie.isAlive()) throw new GameTestAssertException("guard did not damage and defeat the hostile mob");
                    if (!helper.getLevel().getBlockState(log).isAir() || body.inventory().countItem(Items.OAK_LOG) != 1) {
                        throw new GameTestAssertException("resumed MINE did not acquire the real oak log");
                    }
                    var mine = body.executor().arbiter().snapshot(ActionId.of(mineId)).orElseThrow();
                    if (mine.state() != ActionState.COMPLETED) {
                        throw new GameTestAssertException("original MINE did not resume after guard: " + mine.state() + ":" + mine.message());
                    }
                    boolean suspended = body.executor().arbiter().journal().stream()
                            .anyMatch(receipt -> receipt.id().equals(ActionId.of(mineId)) && receipt.decision() == ReceiptDecision.SUSPENDED);
                    boolean resumed = body.executor().arbiter().journal().stream()
                            .anyMatch(receipt -> receipt.id().equals(ActionId.of(mineId)) && receipt.decision() == ReceiptDecision.RESUMED);
                    if (!suspended || !resumed) throw new GameTestAssertException("MINE did not show SUSPENDED/RESUMED checkpoint evidence");
                    long guardIds = body.executor().arbiter().journal().stream()
                            .filter(receipt -> receipt.payload() instanceof BodyOrder order && order.kind() == BodyOrder.Kind.GUARD)
                            .map(receipt -> receipt.id().value()).distinct().count();
                    if (guardIds != 1) throw new GameTestAssertException("same owner damage created duplicate GUARD actions: " + guardIds);
                })
                .thenExecute(() -> retire(owner))
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p2_guard")
    public static void stopDoesNotStartGuard(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(2, 1, 1));
        CompanionEntity stopped = body(helper, new BlockPos(1, 1, 1));
        stopped.setOwner(owner.getUUID());
        stopped.executor().stop("owner stop");
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 1));
        zombie.setNoAi(true);
        helper.setNight();
        helper.runAfterDelay(70, () -> retire(owner));
        helper.startSequence()
                .thenExecuteAfter(65, () -> {
                    if (!zombie.doHurtTarget(owner)) helper.fail("zombie did not damage owner in stop case");
                })
                .thenWaitUntil(() -> {
                    long guardIds = stopped.executor().arbiter().journal().stream()
                            .filter(receipt -> receipt.payload() instanceof BodyOrder order && order.kind() == BodyOrder.Kind.GUARD)
                            .map(receipt -> receipt.id().value()).distinct().count();
                    if (guardIds != 0) throw new GameTestAssertException("stopped or friendly damage created a GUARD action");
                    if (!stopped.executor().stopped()) throw new GameTestAssertException("stop state was cleared by guard observation");
                })
                .thenExecute(() -> retire(owner))
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_p2_guard")
    public static void friendlyDamageDoesNotStartGuard(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(2, 1, 1));
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.setOwner(owner.getUUID());
        var friendly = helper.spawn(EntityType.COW, new BlockPos(1, 1, 3));
        helper.setNight();
        helper.runAfterDelay(90, () -> retire(owner));
        helper.startSequence()
                .thenExecuteAfter(65, () -> {
                    if (!friendly.doHurtTarget(owner)) {
                        helper.fail("friendly damage source did not reach owner");
                    }
                })
                .thenWaitUntil(() -> {
                    if (body.executor().isGuarding()) throw new GameTestAssertException("friendly damage created a GUARD lease");
                    if (body.executor().arbiter().journal().stream().anyMatch(receipt -> receipt.payload() instanceof BodyOrder order && order.kind() == BodyOrder.Kind.GUARD)) {
                        throw new GameTestAssertException("friendly damage created a GUARD receipt");
                    }
                })
                .thenExecute(() -> retire(owner))
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 220, batch = "hearthcrew_p2_guard")
    public static void crossDimensionOwnerDoesNotCreateGuard(GameTestHelper helper) {
        floor(helper);
        ServerPlayer owner = owner(helper, new BlockPos(2, 1, 1));
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.setOwner(owner.getUUID());
        var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("Nether level unavailable for cross-dimension guard gate");
        BlockPos runAnchor = helper.absolutePos(new BlockPos(2, 64, 1));
        BlockPos netherGround = new BlockPos(
                (runAnchor.getX() >> 4 << 4) + 2,
                64,
                (runAnchor.getZ() >> 4 << 4) + 2);
        for (int x = netherGround.getX() - 2; x <= netherGround.getX() + 2; x++)
            for (int z = netherGround.getZ() - 2; z <= netherGround.getZ() + 2; z++) {
                nether.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 65; y <= 67; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        ChunkPos netherChunk = new ChunkPos(netherGround);
        // GameTest advances virtual ticks faster than cold dimension generation.
        // Complete fixture chunk preparation before measuring its tick windows.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            nether.getChunk(netherChunk.x + dx, netherChunk.z + dz);
        String ticketKey = "guard-" + UUID.randomUUID();
        // The Nether is cold in a fresh GameTest run.  This is test-only loading:
        // use a run-local key/type so another GameTest cannot remove this
        // ticket, then wait for the official entity-ticking predicate before
        // touching the zombie/player fixture.
        nether.getChunkSource().addRegionTicket(COLD_GUARD_TICKET, netherChunk, 3, ticketKey, true);
        ServerPlayer ticker = helper.makeMockServerPlayerInLevel();
        ticker.teleportTo(nether, netherGround.getX() + 0.5, netherGround.getY() + 1.0, netherGround.getZ() + 0.5, 0.0F, 0.0F);
        ticker.hasChangedDimension();
        Zombie[] zombieRef = new Zombie[1];
        long[] damageTick = {-1L};
        Runnable cleanup = () -> {
            nether.getChunkSource().removeRegionTicket(COLD_GUARD_TICKET, netherChunk, 3, ticketKey, true);
            if (zombieRef[0] != null && !zombieRef[0].isRemoved()) zombieRef[0].discard();
            retire(ticker);
            retire(owner);
        };
        helper.runAfterDelay(210, cleanup);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!nether.isPositionEntityTicking(netherGround.above())) {
                        throw new GameTestAssertException("waiting for the Nether guard fixture entity-ticking platform at "
                                + netherGround + ", chunk=" + netherChunk + ", netherTime=" + nether.getGameTime());
                    }
                })
                .thenExecute(() -> {
                    Zombie zombie = EntityType.ZOMBIE.create(nether);
                    if (zombie == null) throw new GameTestAssertException("could not construct Nether zombie");
                    zombie.setNoAi(true);
                    zombie.setPersistenceRequired();
                    zombie.moveTo(netherGround.getX() + 0.5, netherGround.getY() + 1.0, netherGround.getZ() + 0.5, 0.0F, 0.0F);
                    if (!nether.addFreshEntity(zombie)) throw new GameTestAssertException("could not add Nether guard zombie");
                    zombieRef[0] = zombie;
                    owner.teleportTo(nether, netherGround.getX() + 0.5, netherGround.getY() + 1.0, netherGround.getZ() + 0.5, 0.0F, 0.0F);
                    // The embedded fixture has no client to ACK the dimension transition.
                    // Complete the same vanilla lifecycle hook as the client ACK; production damage/portal code is unchanged.
                    if (owner.level() != nether || !owner.isAlive()) helper.fail("owner did not arrive alive at the Nether fixture");
                    owner.hasChangedDimension();
                })
                .thenExecuteAfter(65, () -> {
                    Zombie zombie = zombieRef[0];
                    if (zombie == null || !zombie.isAlive()) helper.fail("Nether guard zombie was not alive after readiness");
                    if (!zombie.doHurtTarget(owner)) helper.fail("cross-dimension zombie did not damage owner");
                    damageTick[0] = nether.getGameTime();
                })
                .thenWaitUntil(() -> {
                    if (nether.getGameTime() - damageTick[0] < 20) throw new GameTestAssertException("cross-dimension guard gate must observe a full 20-tick window");
                    if (body.executor().isGuarding()) throw new GameTestAssertException("overworld body guarded an owner in another dimension");
                    if (body.executor().arbiter().journal().stream().anyMatch(receipt -> receipt.payload() instanceof BodyOrder order && order.kind() == BodyOrder.Kind.GUARD)) {
                        throw new GameTestAssertException("cross-dimension owner damage created a GUARD receipt");
                    }
                })
                .thenExecute(cleanup)
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_p2_guard")
    public static void protectedPlacementDoesNotConsumeItem(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.DIRT, 1));
        CrewWorldData.get(helper.getLevel().getServer()).markPlayerBlock(helper.getLevel(), target);
        String id = "p2-protected-place-" + body.getUUID();
        body.executor().submit(id, new BodyOrder(BodyOrder.Kind.PLACE, target, null, 0), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            if (!helper.getLevel().getBlockState(target).isAir()) throw new GameTestAssertException("protected target was modified by PLACE");
            if (body.inventory().getItem(0).getCount() != 1) throw new GameTestAssertException("protected PLACE consumed the item");
            var state = body.executor().arbiter().snapshot(ActionId.of(id)).orElseThrow().state();
            if (state != ActionState.FAILED) throw new GameTestAssertException("protected PLACE did not fail closed: " + state);
        });
    }

    private static ServerPlayer owner(GameTestHelper helper, BlockPos position) {
        // GameTestHelper's convenience player is intentionally creative in 1.21.1.
        // Build the same connected PlayerList entry without the creative override so
        // the damage path remains ordinary survival gameplay.
        var server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "hearthcrew-guard-test");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos absolute = helper.absolutePos(position);
        player.teleportTo(helper.getLevel(), absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        ServerPlayer listed = helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID());
        if (listed == null) throw new GameTestAssertException("GameTest mock owner is not registered in ServerPlayerList; guard evidence would be invalid");
        if (listed.isCreative() || listed.isSpectator()) throw new GameTestAssertException("guard owner was not a survival PlayerList player");
        listed.teleportTo(helper.getLevel(), absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5, 0.0F, 0.0F);
        return listed;
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void retire(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        var server = player.getServer();
        if (server != null && server.getPlayerList().getPlayer(player.getUUID()) == player) {
            server.getPlayerList().remove(player);
        }
        if (!player.isRemoved()) player.discard();
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }
}
