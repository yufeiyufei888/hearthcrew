package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewTeamService;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Durable exclusive ownership gates for finalized local GATHER plans. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2GatherClaimGameTests {
    private P2GatherClaimGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 100, batch = "hearthcrew_p2_gather_claim")
    public static void claimIsIdempotentAndBlocksMineAndSecondGather(GameTestHelper helper) {
        floor(helper);
        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.OAK_LOG);
        CompanionEntity first = body(helper, new BlockPos(1, 1, 1));
        CompanionEntity second = body(helper, new BlockPos(5, 1, 1));
        CrewTeamService service = isolatedService(helper);
        String action = "gather-claim-" + first.getUUID();
        if (!service.canClaimGather(first, action, List.of(target))) helper.fail("candidate was rejected before any claim existed");
        if (!service.tryClaimGather(first, action, List.of(target))) helper.fail("initial GATHER claim rejected");
        if (!service.tryClaimGather(first, action, List.of(target))) helper.fail("exact GATHER retry lost idempotence");
        if (!service.ownsGatherBlock(first, action, target) || service.ownsGatherBlock(second, action, target)) {
            helper.fail("GATHER ownership identity was not body/action scoped");
        }
        if (service.canClaimGather(second, "gather-claim-second", List.of(target))) helper.fail("read-only claim query ignored an owned GATHER block");
        if (service.tryClaimGather(second, "gather-claim-second", List.of(target))) helper.fail("second body claimed an owned GATHER block");
        expectRejected(() -> service.assertIndependentActionAllowed(second, BodyOrder.mine(target)), "MINE ignored the GATHER claim");
        expectRejected(() -> service.propose(work("team-mine-conflict", second, BodyOrder.Kind.MINE, target, 1, null)), "team MINE ignored the GATHER claim");
        expectRejected(() -> service.propose(work("team-gather-rejected", second, BodyOrder.Kind.GATHER, null, 1, "minecraft:oak_log")), "team GATHER was silently admitted");
        if (service.gatherClaims().size() != 1) helper.fail("duplicate claim changed durable claim count");
        first.executor().submit(action, BodyOrder.gather(net.minecraft.resources.ResourceLocation.parse("minecraft:oak_log"), 1), ActionPriority.MISSION);
        first.executor().arbiter().finish(ActionId.of(action), ActionState.RECONCILE_REQUIRED, "fixture uncertain");
        service.tick();
        if (service.gatherClaims().size() != 1) helper.fail("RECONCILE action released its durable claim");
        BlockPos secondTarget = helper.absolutePos(new BlockPos(2, 1, 1));
        String terminalAction = "gather-terminal-" + first.getUUID();
        if (!service.tryClaimGather(first, terminalAction, List.of(secondTarget))) helper.fail("second independent candidate was not claimable");
        first.executor().submit(terminalAction, BodyOrder.gather(net.minecraft.resources.ResourceLocation.parse("minecraft:oak_log"), 1), ActionPriority.MISSION);
        first.executor().arbiter().finish(ActionId.of(terminalAction), ActionState.FAILED, "fixture terminal");
        service.tick();
        if (service.gatherClaims().size() != 1 || service.gatherClaims().stream().anyMatch(claim -> claim.actionId().equals(terminalAction))) {
            helper.fail("exact FAILED arbiter outcome did not release only its claim");
        }
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 120, batch = "hearthcrew_p2_gather_claim")
    public static void unknownBodyClaimSurvivesArchiveReload(GameTestHelper helper) {
        floor(helper);
        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.OAK_LOG);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        CrewWorldData data = new CrewWorldData();
        CrewTeamService first = new CrewTeamService(helper.getLevel().getServer(), data);
        String action = "gather-unknown-" + body.getUUID();
        first.tryClaimGather(body, action, List.of(target));
        CrewTeamService restored = new CrewTeamService(helper.getLevel().getServer(), data);
        if (restored.gatherClaims().size() != 1 || !restored.ownsGatherBlock(body, action, target)) {
            helper.fail("GATHER claim was lost across TeamStateCodec archive reload");
        }
        body.discard();
        restored.tick();
        if (restored.gatherClaims().size() != 1) helper.fail("missing body caused an unknown GATHER claim to be released");
        helper.succeed();
    }

    private static CrewTeamService isolatedService(GameTestHelper helper) {
        return new CrewTeamService(helper.getLevel().getServer(), new CrewWorldData());
    }

    private static CrewTeamService.Work work(String id, CompanionEntity body, BodyOrder.Kind kind,
                                              BlockPos position, int count, String resource) {
        return new CrewTeamService.Work(id, id + "-intent", body.companionId(), body.bodyGeneration(),
                body.level().dimension().location().toString(), kind,
                position == null ? null : new CrewTeamService.Position(position.getX(), position.getY(), position.getZ()),
                null, count, resource, "GATHER claim fixture");
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static void expectRejected(Runnable action, String message) {
        try { action.run(); throw new GameTestAssertException(message); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
