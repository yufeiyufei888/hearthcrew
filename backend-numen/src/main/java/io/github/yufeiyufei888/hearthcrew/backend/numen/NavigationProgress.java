package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Root-owned progress, independent of native search clocks and model turns. */
final class NavigationProgress {
    private final Map<BlockPos,String> rejected=new HashMap<>();
    private List<BlockPos> batch=List.of();
    private BlockPos anchor;
    private double best=Double.MAX_VALUE;
    private int lastMutations,lastStep;
    private long idle,ticks,lastProgress;
    private int retries;
    private String phase="SEARCHING",reason="NO_VERIFIED_PROGRESS";
    void candidates(NumenPlayer body,List<BlockPos> candidates) {
        candidates.removeIf(p->rejected.containsKey(p)&&fingerprint(body,p).equals(rejected.get(p)));
        // Keep a stable batch while its real blocks still exist. Re-querying must not move the goal.
        var selected=new ArrayList<BlockPos>();
        for(var p:batch)if(candidates.contains(p))selected.add(p);
        if(selected.isEmpty()) {
            candidates.sort(Comparator.<BlockPos>comparingInt(p->visible(body,p)?0:1).thenComparingDouble(p->p.distSqr(body.blockPosition())).thenComparingLong(BlockPos::asLong));
            selected.addAll(candidates.subList(0,Math.min(4,candidates.size())));
        }
        batch=List.copyOf(selected);candidates.retainAll(batch);
        if(!batch.isEmpty())target(body,batch.getFirst());
    }
    void target(NumenPlayer body,BlockPos p){if(p!=null&&!p.equals(anchor)){anchor=p;best=body.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(anchor));}}
    private static boolean visible(NumenPlayer body,BlockPos p){
        var hit=body.level().clip(new net.minecraft.world.level.ClipContext(body.getEyePosition(),net.minecraft.world.phys.Vec3.atCenterOf(p),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,body));
        return body.onGround()&&body.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))<=20.25&&hit.getBlockPos().equals(p);
    }
    private static String fingerprint(NumenPlayer body,BlockPos p){
        var s=new StringBuilder();for(var q:BlockPos.betweenClosed(p.offset(-1,-1,-1),p.offset(1,2,1)))s.append(body.level().getBlockState(q)).append(';');return s.toString();
    }
    void failed(NumenPlayer body,String cause){
        reason=cause;
        for(var p:batch)rejected.put(p,fingerprint(body,p));batch=List.of();retries++;phase="REPLANNING";
        // Deliberately retain root idle/anchor: changing the candidate is not actual progress.
    }
    boolean tick(NumenPlayer body,int mutations,int completedSteps,boolean searching) {
        ticks++;boolean changed=mutations>lastMutations||completedSteps>lastStep;
        if(changed){lastMutations=mutations;lastStep=completedSteps;anchor=batch.isEmpty()?null:batch.getFirst();best=Double.MAX_VALUE;}
        if(anchor!=null&&body.onGround()&&!body.isInWater()){
            double distance=body.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(anchor));
            if(distance<best-1){changed=true;best=distance;}
        }
        if(changed){idle=0;lastProgress=ticks;}else idle++;
        phase=mutations>0&&!searching?"COLLECTING":searching?"SEARCHING":"APPROACHING";
        return idle>=600;
    }
    Map<String,Object> snapshot(){return Map.of("phase",phase,"reason",reason,"idleTicks",idle,"lastProgressTick",lastProgress,"retries",retries,"candidateCount",batch.size(),"rejectedCandidates",rejected.size(),"candidates",batch.stream().map(p->Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ())).toList());}
}
