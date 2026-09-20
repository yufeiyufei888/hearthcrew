package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression for native pickup attribution when a nearby teammate collects a mined drop. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2PickupGameTests {
    @GameTest(template = "p0_empty", timeoutTicks = 320, batch = "hearthcrew_p2_pickup")
    public static void nearbyTeammateLeavesMinedDropForWorker(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 6; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos log = helper.absolutePos(new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.OAK_LOG);
        CompanionEntity worker = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(1, 1, 2)); worker.setRespawnEnabled(false);
        CompanionEntity other = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(6, 1, 4)); other.setRespawnEnabled(false);
        String mineId = "p2-owned-drop-" + worker.getUUID();
        worker.executor().submit(mineId, BodyOrder.mine(log), ActionPriority.MISSION);
        helper.startSequence().thenWaitUntil(() -> {
            if (!helper.getLevel().getBlockState(log).isAir()) throw new GameTestAssertException("waiting for real mining");
        }).thenExecute(() -> {
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(log).inflate(2));
            if (drops.size() != 1) helper.fail("fixture expected one actual mined log drop");
            ItemEntity drop = drops.getFirst();
            // Fixture placement intentionally creates contact before the owner arrives. This proves pickup ownership, not navigation.
            other.moveTo(drop.getX(), drop.getY(), drop.getZ(), 0, 0);
            drop.setNoPickUpDelay();
            worker.executor().pause();
        }).thenExecuteAfter(6, () -> {
            if (other.inventory().countItem(Items.OAK_LOG) != 1) helper.fail("nearby teammate did not receive the native pickup event");
            worker.executor().resume();
        }).thenWaitUntil(() -> {
            var action = worker.executor().arbiter().snapshot(ActionId.of(mineId)).orElseThrow();
            if (action.state() != ActionState.COMPLETED) throw new GameTestAssertException("worker mining result: " + action.state() + ":" + action.message());
            if (worker.inventory().countItem(Items.OAK_LOG) + other.inventory().countItem(Items.OAK_LOG) != 1)
                helper.fail("mined log was not acquired exactly once across the team");
        }).thenSucceed();
    }
}
