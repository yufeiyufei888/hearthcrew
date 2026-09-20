package io.github.yufeiyufei888.hearthcrew.backend.numen;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import java.util.*;
/** The same feet, support and visible interaction face for probing and execution. */
final class InteractionStances {
 record Stance(BlockPos cell,Vec3 feet,BlockPos target){}
 static List<Stance> around(NumenPlayer body,BlockPos target){
  var result=new ArrayList<Stance>();var seen=new HashSet<BlockPos>();
  for(var p:BlockPos.betweenClosed(target.offset(-4,-4,-4),target.offset(4,3,4))){
   if(!body.level().hasChunkAt(p)||!body.level().getFluidState(p).isEmpty())continue;
   var shape=body.level().getBlockState(p).getCollisionShape(body.level(),p);if(shape.isEmpty())continue;
   var feet=new Vec3(p.getX()+.5,p.getY()+shape.max(Direction.Axis.Y),p.getZ()+.5);
   var cell=BlockPos.containing(feet);if(seen.contains(cell))continue;
   var box=body.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(feet);
   if(!body.level().noCollision(body,box)||body.level().noCollision(body,box.move(0,-.05,0))||!body.level().getFluidState(cell).isEmpty())continue;
   if(!visible(body,feet,target))continue;
   seen.add(cell);result.add(new Stance(cell.immutable(),feet,target.immutable()));
  }
  result.sort(Comparator.comparingDouble(s->s.feet().distanceToSqr(body.position())));return result;
 }
 static boolean visible(NumenPlayer body,Vec3 feet,BlockPos target){
  var eye=feet.add(0,body.getEyeHeight(),0);var center=Vec3.atCenterOf(target);
  if(eye.distanceToSqr(center)>20.25)return false;
  var ray=body.level().clip(new ClipContext(eye,center,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,body));
  return ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(target);
 }
}
