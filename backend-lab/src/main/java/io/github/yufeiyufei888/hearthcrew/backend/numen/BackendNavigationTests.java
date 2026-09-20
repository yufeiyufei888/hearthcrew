package io.github.yufeiyufei888.hearthcrew.backend.numen;

import io.github.yufeiyufei888.hearthcrew.backend.CompanionBackend;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("hearthcrew_backend_navigation")
@PrefixGameTestTemplate(false)
public final class BackendNavigationTests {
    @GameTest(template="empty",timeoutTicks=1800,batch="navigation-long-land")
    public static void actualHundredBlockLandRouteUsesNativeMovement(GameTestHelper h){
        var players=h.getLevel().getServer().getPlayerList();System.out.println("BACKEND_NAVIGATION_FIXTURE originalView="+players.getViewDistance()+" originalSimulation="+players.getSimulationDistance());
        // GameTestServer's bare PlayerList leaves both distances at zero. This
        // success fixture uses explicit ordinary server distances; no forced route tickets.
        players.setViewDistance(6);players.setSimulationDistance(6);
        var b=BackendGateTests.spawn(h);var origin=b.body().blockPosition();var target=origin.east(100);
        // Explicit loaded corridor fixture, not a gameplay-generated route or world ticket.
        for(int x=-1;x<=102;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=2;y++)h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        long[] started={0},progressTick={0};Vec3[] previous={null};double[] maximumStep={0},best={100};long generation=b.snapshot().identity().generation();
        h.runAfterDelay(6,()->{started[0]=h.getLevel().getGameTime();previous[0]=b.body().position();
            b.dispatch(new CompanionBackend.Request(b.snapshot().identity(),"hundred-block-land",BodyOrder.move(target),ActionPriority.OWNER));});
        h.onEachTick(()->{if(previous[0]==null)return;var p=b.body().position();maximumStep[0]=Math.max(maximumStep[0],p.distanceTo(previous[0]));previous[0]=p;
            double distance=p.distanceTo(Vec3.atBottomCenterOf(target));long elapsed=h.getLevel().getGameTime()-started[0];
            if(distance<best[0]-.1){best[0]=distance;progressTick[0]=elapsed;}
            if(elapsed>0&&elapsed%100==0){var box=b.body().getBoundingBox().move(.4,0,0);var shapes=new java.util.ArrayList<Object>();h.getLevel().getBlockCollisions(b.body(),box).forEach(s->shapes.add(s.toAabbs()));
                System.out.println("BACKEND_NAVIGATION_DIAGNOSTIC pos="+p+" bodyTicks="+b.body().tickCount+" lastSection="+b.body().getLastSectionPos()+" entityTicking="+h.getLevel().isPositionEntityTicking(b.body().blockPosition())+" input="+b.body().zza+","+b.body().xxa+" yaw="+b.body().getYRot()+" velocity="+b.body().getDeltaMovement()+" collision="+b.body().horizontalCollision+" shapes="+shapes+" nearbyEntities="+h.getLevel().getEntities(b.body(),box.inflate(.5)).stream().map(e->e.getType()+":"+e.position()).toList()+" loaded="+h.getLevel().hasChunkAt(target)+" noCollision="+h.getLevel().noCollision(b.body(),box));}
            if(elapsed-progressTick[0]>230){b.cancel();b.close();previous[0]=null;h.fail("Known long route stopped for over 230 ticks; see BACKEND_NAVIGATION_DIAGNOSTIC");return;}
            h.assertTrue(maximumStep[0]<1.2&&b.snapshot().identity().generation()==generation,"no teleport or abnormal per-tick displacement");
            if(!b.terminal())return;
            h.assertTrue(b.snapshot().state().equals("SUCCESS")&&p.distanceTo(Vec3.atBottomCenterOf(target))<1.2&&b.body().onGround(),"known loaded hundred-block route must actually finish; state="+b.snapshot().state());
            h.assertTrue(((Number)b.diagnostics().get("maxSearchNodesPerBodyTick")).intValue()<=64,"one shared body search budget");
            h.assertTrue(((Number)b.execution().get("primaryBroken")).intValue()==0&&((Number)b.execution().get("accessSpent")).intValue()==0,"ordinary movement never changes terrain");
            System.out.println("BACKEND_NAVIGATION_DISTANCE blocks=100 ticks="+(h.getLevel().getGameTime()-started[0])+" maximumPerTick="+maximumStep[0]+" modelCalls=0");b.close();h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=700,batch="navigation-slabs-stairs")
    public static void halfHeightAndStairsMustBeTraversedRatherThanReportedArrived(GameTestHelper h){
        var b=BackendGateTests.spawn(h);var origin=b.body().blockPosition();var target=origin.east(13);
        // Enclosed straight route: the slabs/stair cannot be silently bypassed on open floor.
        for(int x=-1;x<=15;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=3;y++) {
            var state=y==-1||Math.abs(z)==1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();h.getLevel().setBlockAndUpdate(origin.offset(x,y,z),state);
        }
        for(int x=3;x<=5;x++)h.getLevel().setBlockAndUpdate(origin.east(x),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM));
        h.getLevel().setBlockAndUpdate(origin.east(6),Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.EAST));
        for(int x=7;x<=8;x++)h.getLevel().setBlockAndUpdate(origin.east(x),Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(origin.east(9),Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.WEST));
        boolean[] atHalf={false},atTop={false},started={false};
        h.runAfterDelay(6,()->{b.dispatch(new CompanionBackend.Request(b.snapshot().identity(),"half-and-stairs",BodyOrder.move(target),ActionPriority.OWNER));started[0]=true;});
        h.onEachTick(()->{if(!started[0])return;var p=b.body().position();double relative=p.x-origin.getX();
            if(relative>=3.5&&relative<=5.5&&b.body().onGround()&&Math.abs(p.y-origin.getY()-.5)<.05)atHalf[0]=true;
            if(relative>=7&&relative<=9&&p.y-origin.getY()>.95)atTop[0]=true;
            if(!b.terminal())return;h.assertTrue(b.snapshot().state().equals("SUCCESS")&&atHalf[0]&&atTop[0]&&p.distanceTo(Vec3.atBottomCenterOf(target))<1.2,"actual fractional feet and upper landing must be traversed; state="+b.snapshot().state()+" half="+atHalf[0]+" top="+atTop[0]);
            b.close();h.succeed();});
    }
}
