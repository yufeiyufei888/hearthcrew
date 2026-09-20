package io.github.yufeiyufei888.hearthcrew.gameplay;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Nearby loaded terrain, separate from resource counts. Candidates are not path proofs. */
public final class TravelTerrain {
    private TravelTerrain() {}
    public static boolean clear(CompanionEntity body, BlockPos feet) {
        return clearAt(body,Vec3.atBottomCenterOf(feet));
    }
    public static boolean clearAt(CompanionEntity body,Vec3 feet) {
        var p=BlockPos.containing(feet);
        if(!body.level().hasChunkAt(p)||!body.level().getWorldBorder().isWithinBounds(p))return false;
        var box=body.getBoundingBox().move(feet.subtract(body.position())).deflate(1.0e-5);
        // Dynamic companions do not turn a static route into a permanent wall.
        for(var shape:body.level().getBlockCollisions(body,box))if(!shape.isEmpty())return false;
        return true;
    }
    public static boolean sightIgnoring(CompanionEntity body,Vec3 eye,BlockPos target,Set<BlockPos> removed){
        var end=Vec3.atCenterOf(target);
        for(var p:BlockPos.betweenClosed(BlockPos.containing(Math.min(eye.x,end.x),Math.min(eye.y,end.y),Math.min(eye.z,end.z)),BlockPos.containing(Math.max(eye.x,end.x),Math.max(eye.y,end.y),Math.max(eye.z,end.z)))){
            if(p.equals(target)||removed.contains(p))continue;
            if(!body.level().hasChunkAt(p))return false;
            var shape=body.level().getBlockState(p).getCollisionShape(body.level(),p);
            if(!shape.isEmpty()&&shape.clip(eye,end,p)!=null)return false;
        }return true;
    }
    public static boolean clearIgnoring(CompanionEntity body,Vec3 feet,Set<BlockPos> removed){
        var box=body.getBoundingBox().move(feet.subtract(body.position())).deflate(1e-5);
        for(var p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ))){
            if(!body.level().hasChunkAt(p))return false;if(removed.contains(p))continue;
            for(var part:body.level().getBlockState(p).getCollisionShape(body.level(),p).toAabbs())if(part.move(p).intersects(box))return false;
        }return true;
    }
    public static List<UUID> blockers(CompanionEntity body,Vec3 feet) {
        return body.level().getEntities(body,body.getBoundingBox().move(feet.subtract(body.position())).deflate(.03),e->e.isAlive()&&e.isPushable()).stream().map(e->e.getUUID()).toList();
    }
    /** Grid keys use ceil(support height); the motor uses the actual collision surface. */
    public static Vec3 feetPoint(CompanionEntity body,BlockPos key) {
        var floor=key.below();double top=Double.NEGATIVE_INFINITY;
        var shape=body.level().getBlockState(floor).getCollisionShape(body.level(),floor);
        double half=body.getBbWidth()/2.0-.01;
        for(var box:shape.toAabbs())if(box.maxX>.5-half&&box.minX<.5+half&&box.maxZ>.5-half&&box.minZ<.5+half)top=Math.max(top,box.maxY);
        return new Vec3(key.getX()+.5,Double.isFinite(top)?floor.getY()+top:key.getY(),key.getZ()+.5);
    }
    public static BlockPos supportedStart(CompanionEntity body) {
        var key=BlockPos.containing(body.getX(),Math.ceil(body.getY()-1e-4),body.getZ());
        if(standable(body,key))return key;
        for(var p:BlockPos.betweenClosed(key.offset(-1,-1,-1),key.offset(1,1,1))) {
            if(!standable(body,p))continue;var point=feetPoint(body,p);
            var footprint=body.getBoundingBox();
            if(Math.abs(point.y-body.getY())<.15 && footprint.maxX>p.getX() && footprint.minX<p.getX()+1 && footprint.maxZ>p.getZ() && footprint.minZ<p.getZ()+1)return p.immutable();
        }
        return key;
    }
    public static boolean dangerous(CompanionEntity body, BlockPos pos) {
        var state=body.level().getBlockState(pos);
        return state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.MAGMA_BLOCK)||state.is(Blocks.CACTUS)
                ||state.is(Blocks.CAMPFIRE)||state.is(Blocks.SOUL_CAMPFIRE)||state.is(Blocks.POWDER_SNOW)||state.is(Blocks.SWEET_BERRY_BUSH)
                ||body.level().getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA);
    }
    public static boolean standable(CompanionEntity body, BlockPos feet) {
        var level=body.level();
        if (!level.hasChunkAt(feet)||level.getBlockState(feet.below()).getCollisionShape(level,feet.below()).isEmpty())return false;
        for (var pos:List.of(feet.below(),feet,feet.above())) if (dangerous(body,pos)||!level.getFluidState(pos).isEmpty())return false;
        return clearAt(body,feetPoint(body,feet));
    }
    /** Validate the swept body volume, rather than only the two endpoint cells. */
    public static boolean transition(CompanionEntity body,BlockPos from,BlockPos to) {
        if(Math.abs(to.getY()-from.getY())>3 || Math.abs(to.getX()-from.getX())+Math.abs(to.getZ()-from.getZ())>1)return false;
        Vec3 a=standable(body,from)?feetPoint(body,from):Vec3.atBottomCenterOf(from),b=standable(body,to)?feetPoint(body,to):Vec3.atBottomCenterOf(to);
        if(b.y-a.y>1.25||a.y-b.y>3)return false;
        double upper=Math.max(a.y,b.y);
        Vec3 highA=new Vec3(a.x,upper,a.z),highB=new Vec3(b.x,upper,b.z);
        for(int i=0;i<=12;i++)if(!clearAt(body,a.lerp(highA,i/12d))||!clearAt(body,highA.lerp(highB,i/12d))||!clearAt(body,highB.lerp(b,i/12d)))return false;
        return true;
    }
    public static boolean landedAt(CompanionEntity body,BlockPos target,double horizontalTolerance) {
        var d=body.position().subtract(feetPoint(body,target));
        return standable(body,target)&&body.onGround()&&!body.isInWater()&&Math.abs(d.y)<.15
            &&d.horizontalDistanceSqr()<horizontalTolerance*horizontalTolerance
            && Math.abs(body.getDeltaMovement().y)<.08;
    }
    public static boolean breathable(CompanionEntity body, BlockPos feet) {
        return clear(body,feet)&&body.level().getFluidState(feet.above()).isEmpty()
                &&!dangerous(body,feet)&&!dangerous(body,feet.above());
    }
    /** Directional land candidates are ranked before limiting; never reuse the nearest-shore list. */
    public static List<BlockPos> frontier(CompanionEntity body, Vec3 destination, int radius) {
        var center=body.blockPosition(); var found=new ArrayList<BlockPos>();
        double initial=body.position().distanceToSqr(destination);
        for(int dx=-radius;dx<=radius;dx+=2)for(int dz=-radius;dz<=radius;dz+=2){
            if(dx*dx+dz*dz>radius*radius||dx*dx+dz*dz<16)continue;
            for(int dy=12;dy>=-12;dy--){
                var at=center.offset(dx,dy,dz);
                if(!body.level().hasChunkAt(at)||!standable(body,at))continue;
                if(feetPoint(body,at).distanceToSqr(destination)<initial-4)found.add(at);
                // Multiple heights may be usable; an upper canopy must not hide the ground.
            }
        }
        found.sort(Comparator.comparingDouble(p->feetPoint(body,p).distanceToSqr(destination)));
        return found.stream().limit(32).toList();
    }
    public static List<BlockPos> shores(CompanionEntity body,int radius) {
        List<BlockPos> found=new ArrayList<>(); BlockPos center=body.blockPosition();
        // Two-block horizontal sampling is disclosed to the model; thin shores may be missed.
        for(int dx=-radius;dx<=radius;dx+=2)for(int dz=-radius;dz<=radius;dz+=2)
            for(int dy=Math.min(12,radius);dy>=-Math.min(8,radius);dy--) {
                BlockPos p=center.offset(dx,dy,dz);
                if(p.distSqr(center)>radius*radius||!body.level().hasChunkAt(p))continue;
                if(standable(body,p)){found.add(p);break;}
            }
        found.sort(Comparator.comparingDouble(p->p.distToCenterSqr(body.position())));
        return found.stream().limit(24).toList();
    }
    public static List<BlockPos> air(CompanionEntity body,int radius) {
        List<BlockPos> found=new ArrayList<>(); var center=body.blockPosition();
        for(int dy=0;dy<=radius;dy++)for(int dx=-4;dx<=4;dx+=2)for(int dz=-4;dz<=4;dz+=2) {
            var pos=center.offset(dx,dy,dz);
            if(pos.distSqr(center)<=radius*radius&&breathable(body,pos))found.add(pos);
        }
        found.sort(Comparator.comparingDouble(p->p.distToCenterSqr(body.position())));
        return found.stream().limit(16).toList();
    }
    public static Map<String,Object> position(BlockPos pos){return Map.of("x",pos.getX(),"y",pos.getY(),"z",pos.getZ());}
    public static Map<String,Object> observe(CompanionEntity body,int radius) {
        var ground=shores(body,radius);
        var banks=ground.stream().filter(p->java.util.stream.Stream.of(p.north(),p.south(),p.east(),p.west())
            .anyMatch(n->body.level().hasChunkAt(n) && (body.level().getFluidState(n).is(net.minecraft.tags.FluidTags.WATER)
                || body.level().getFluidState(n.below()).is(net.minecraft.tags.FluidTags.WATER)))).toList();
        return Map.of("inWater",body.isInWater(),"eyesUnderWater",body.isUnderWater(),"air",body.getAirSupply(),
            "standable",ground.stream().map(TravelTerrain::position).toList(),
            "shoreCandidates",banks.stream().map(TravelTerrain::position).toList(),
            "breathable",air(body,radius).stream().map(TravelTerrain::position).toList(),
            "candidateReachability","unverified", "horizontalSampleStep",2,"radius",radius);
    }
}
