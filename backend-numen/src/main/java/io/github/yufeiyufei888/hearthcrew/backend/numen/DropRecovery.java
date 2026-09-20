package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import java.util.*;

/** Walk to evidence-linked drops. Only the normal pickup event records acquisition. */
final class DropRecovery {
    private final NumenPlayer body;
    private final List<DropProof> proofs;
    private final Item item;
    private final BlockPos origin;
    private final boolean allowAccess;
    private final Set<UUID> skipped=new HashSet<>();
    private final List<Map<String,Object>> attempts=new ArrayList<>();
    private ItemEntity target;
    private PlayerNav nav;
    private int idleTicks,arrivalTicks,replans;
    private double bestDistance=Double.MAX_VALUE;
    DropRecovery(NumenPlayer body,List<DropProof> proofs,Item item,BlockPos origin,boolean allowAccess) {
        this.body=body;this.proofs=List.copyOf(proofs);this.item=item;this.origin=origin;this.allowAccess=allowAccess;
    }
    boolean tick() {
        if(target==null||target.isRemoved()) {
            stop();target=DropProof.visiblePending(body.serverLevel(),proofs,item,origin).stream()
                .filter(e->!skipped.contains(e.getUUID())).min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
            if(target==null)return true;
            idleTicks=0;arrivalTicks=0;replans=0;bestDistance=body.distanceToSqr(target);
        }
        if(nav==null)nav=PlayerNav.to(body,this::pickupGoal,1.0,()->target.isRemoved(),
            allowAccess?PlayerNav.ContextProvider.TERRAFORM:PlayerNav.ContextProvider.DEFAULT);
        double distance=body.distanceToSqr(target);
        if(distance<bestDistance-.25){bestDistance=distance;idleTicks=0;}else idleTicks++;
        var state=nav.tick();
        if(state==PlayerNav.Status.ARRIVED&&!target.isRemoved()
                &&!body.getBoundingBox().inflate(1,.5,1).intersects(target.getBoundingBox()))centerWithinArrivedCell();
        if(state==PlayerNav.Status.ARRIVED)arrivalTicks++;else arrivalTicks=0;
        // Arrival is never credited as collection. Allow native pickup delay, then rotate candidates.
        if(state==PlayerNav.Status.FAILED||arrivalTicks>20||idleTicks>=100) {
            attempts.add(Map.of("target",target.getUUID().toString(),"dropPosition",target.position().toString(),
                "bodyPosition",body.position().toString(),"state",state.name(),"reason",nav.failReason(),
                "inNativePickupBox",body.getBoundingBox().inflate(1,.5,1).intersects(target.getBoundingBox()),"idleTicks",idleTicks));
            nav.stop();nav=null;
            if(replans++==0){idleTicks=0;arrivalTicks=0;}
            else {skipped.add(target.getUUID());target=null;}
        }
        return false;
    }
    private void centerWithinArrivedCell() {
        // Node-domain arrival can leave the player at a cell edge. Complete only the
        // sub-block approach with ordinary input, checking swept collision and support.
        // Do not jump, change terrain, pull the item, or count this motion as pickup.
        if(!body.onGround()||body.isInWater()||body.isPassenger())return;
        var center=net.minecraft.world.phys.Vec3.atBottomCenterOf(body.blockPosition());
        var delta=new net.minecraft.world.phys.Vec3(center.x-body.getX(),0,center.z-body.getZ());
        if(delta.lengthSqr()<.0025)return;
        var step=delta.normalize().scale(Math.min(.15,delta.length()));
        var box=body.getBoundingBox();var next=box.move(step);
        if(!body.level().noCollision(body,box.expandTowards(step))||body.level().noCollision(body,next.move(0,-.08,0)))return;
        com.dwinovo.numen.entity.InputDriver.stepToward(body,center,false);
        body.zza=(float)Math.min(.35,delta.length()*2);
    }
    private com.dwinovo.numen.core.pathing.goal.GoalCompiler.Compiled pickupGoal() {
        // An item is not a block-interaction objective. Search every nearby stance whose
        // normal player pickup box overlaps its current entity box, including lower feet.
        // Exact-cell goals leave no target block sacred and never enlarge actual pickup.
        var goals=new ArrayList<com.dwinovo.numen.core.pathing.calc.NavGoal>();
        var center=target.blockPosition();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-2;dy<=1;dy++) {
            var cell=center.offset(dx,dy,dz);
            if(!body.level().hasChunkAt(cell))continue;
            var point=net.minecraft.world.phys.Vec3.atBottomCenterOf(cell);
            var box=body.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(point);
            if(!box.inflate(1,.5,1).intersects(target.getBoundingBox()))continue;
            if(!body.level().getFluidState(cell).isEmpty())continue;
            if(body.level().noCollision(body,box)&&!body.level().noCollision(body,box.move(0,-.08,0)))
                goals.add(com.dwinovo.numen.core.pathing.calc.NavGoal.exact(cell));
        }
        // A genuinely sealed drop may require the already-authorized access budget.
        if(goals.isEmpty())goals.add(com.dwinovo.numen.core.pathing.calc.NavGoal.exact(center));
        return new com.dwinovo.numen.core.pathing.goal.GoalCompiler.Compiled(
            com.dwinovo.numen.core.pathing.calc.NavGoal.composite(goals),it.unimi.dsi.fastutil.longs.LongSets.emptySet());
    }
    void stop(){if(nav!=null){nav.stop();nav=null;}}
    Map<String,Object> evidence(){return Map.of("skipped",skipped.stream().map(UUID::toString).toList(),"visibleRemaining",DropProof.visiblePending(body.serverLevel(),proofs,item,origin).size(),"attempts",List.copyOf(attempts));}
}
