package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class NavigationArrivalGameTests {
    @GameTest(template = "p0_empty", timeoutTicks = 180, batch = "hearthcrew_navigation_arrival")
    public static void closesGapOutsideArrivalRadius(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        var target = helper.absolutePos(new BlockPos(3, 1, 2));
        Vec3 destination = Vec3.atBottomCenterOf(target);
        var body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, new BlockPos(2, 1, 2));
        body.setRespawnEnabled(false);
        body.moveTo(destination.x - 1.28, destination.y, destination.z, 0, 0);
        Vec3 initial = body.position();
        body.executor().submit("arrival-gap", BodyOrder.move(target), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            var state = body.executor().arbiter().snapshot(ActionId.of("arrival-gap")).orElseThrow().state();
            if (state != ActionState.COMPLETED) throw new GameTestAssertException("last movement gap unresolved: " + state + " at " + body.position());
            helper.assertTrue(body.position().distanceToSqr(destination) <= 1.25 * 1.25, "arrival must use the same actual position tolerance");
            helper.assertTrue(body.position().distanceToSqr(initial) > 0.0001, "completion requires actual movement, not a relaxed assertion");
        });
    }
}
