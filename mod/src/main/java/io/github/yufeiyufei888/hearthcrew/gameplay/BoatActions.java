package io.github.yufeiyufei888.hearthcrew.gameplay;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.FluidTags;
/** Real boat item placement and native rider inputs; no vehicle teleport or generated items. */
public final class BoatActions {
 public static PlayerInteractions.Result launch(CompanionEntity p,BlockPos pos,ResourceLocation resource){
  if(pos!=null&&!p.level().getFluidState(pos).is(FluidTags.WATER)){
   var bank=pos;
   var water=launchWater(p,bank);
   if(water.isEmpty())return new PlayerInteractions.Result(false,0,"BANK_HAS_NO_VISIBLE_LAUNCH_WATER: bank="+bank+"; inspect water surface separately");
   pos=water.getFirst();
  }
  int slot=-1;
  for(int i=0;i<36;i++){var item=p.inventory().getItem(i);if(item.getItem() instanceof BoatItem&&(resource==null||net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).equals(resource))){slot=i;break;}}
  if(slot<0)return new PlayerInteractions.Result(false,0,"MISSING_BOAT_ITEM: full inventory has no "+(resource==null?"usable boat":resource));
  p.selectSlot(slot);
  if(!(p.getMainHandItem().getItem() instanceof BoatItem))return new PlayerInteractions.Result(false,0,"BOAT_SELECTION_FAILED: inventory changed");
  if(pos!=null&&p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))>20)return new PlayerInteractions.Result(false,0,"LAUNCH_OUT_OF_REACH: approach a visible bank first");
  if(pos==null||!p.level().hasChunkAt(pos)||!p.level().getFluidState(pos).is(FluidTags.WATER)||p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))>20)return new PlayerInteractions.Result(false,0,"INVALID_LAUNCH_WATER");
  var known=new HashSet<UUID>();p.level().getEntitiesOfClass(Boat.class,p.getBoundingBox().inflate(6)).forEach(b->known.add(b.getUUID()));
  int count=p.getMainHandItem().getCount();p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,Vec3.atCenterOf(pos).add(0,.45,0));
  var hit=p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(),p.getEyePosition().add(p.getViewVector(1).scale(5)),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.ANY,p));
  if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK||!p.level().getFluidState(hit.getBlockPos()).is(FluidTags.WATER))return new PlayerInteractions.Result(false,0,"LAUNCH_RAY_BLOCKED: actual hit="+hit.getBlockPos());
  if(hit.getBlockPos().distSqr(pos)>2)return new PlayerInteractions.Result(false,0,"LAUNCH_WRONG_WATER: choose visible water surface");
  var space=new net.minecraft.world.phys.AABB(hit.getLocation().add(-.6875,0,-.6875),hit.getLocation().add(.6875,.5625,.6875));
  if(!p.level().noCollision(p,space))return new PlayerInteractions.Result(false,0,"LAUNCH_SPACE_BLOCKED: hull needs clear water surface");
  var used=p.gameMode.useItem(p,p.level(),p.getMainHandItem(),InteractionHand.MAIN_HAND);
  var created=p.level().getEntitiesOfClass(Boat.class,p.getBoundingBox().inflate(6)).stream().filter(b->!known.contains(b.getUUID())).toList();
  if(created.size()==1&&p.getMainHandItem().getCount()==count-1){var boat=created.getFirst();boat.getPersistentData().putUUID("HearthCrewOwner",p.getUUID());return new PlayerInteractions.Result(true,1,"boat="+boat.getUUID()+"; consumed=1");}
  int consumed=count-p.getMainHandItem().getCount();
  return new PlayerInteractions.Result(false,Math.max(created.size(),Math.abs(consumed)),(created.isEmpty()&&consumed==0?"LAUNCH_NO_EFFECT":"RECONCILE_REQUIRED")+": use="+used+"; consumed="+consumed+"; boats="+created.stream().map(e->e.getUUID().toString()).toList()+"; hit="+hit.getBlockPos());
 }
 public static List<BlockPos> launchWater(CompanionEntity p,BlockPos bank){
  var result=new ArrayList<BlockPos>();
  for(var pos:BlockPos.betweenClosed(bank.offset(-2,-1,-2),bank.offset(2,1,2))){
   if(!p.level().hasChunkAt(pos)||!p.level().getFluidState(pos).is(FluidTags.WATER)||!p.level().getFluidState(pos.above()).isEmpty())continue;
   var surface=Vec3.atCenterOf(pos).add(0,.45,0);
   if(surface.distanceToSqr(p.getEyePosition())>20)continue;
   var hit=p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(),surface,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.ANY,p));
   if(hit.getBlockPos().equals(pos))result.add(pos.immutable());
  }
  result.sort(Comparator.comparingDouble(v->v.distSqr(bank)));return result;
 }
 public static PlayerInteractions.Result board(CompanionEntity p,UUID id){
  var target=p.serverLevel().getEntity(id);if(!(target instanceof Boat b)||!b.isAlive())return new PlayerInteractions.Result(false,0,"BOAT_MISSING");
  if(b.getControllingPassenger()!=null&&b.getControllingPassenger()!=p)return new PlayerInteractions.Result(false,0,"BOAT_DRIVER_OCCUPIED");
  if(!b.getPersistentData().hasUUID("HearthCrewOwner"))return new PlayerInteractions.Result(false,0,"BOAT_OWNERSHIP_UNKNOWN");
  if(p.distanceToSqr(b)>9||!p.hasLineOfSight(b))return new PlayerInteractions.Result(false,0,"BOAT_OUT_OF_REACH");
  p.interactOn(b,InteractionHand.MAIN_HAND);return new PlayerInteractions.Result(p.getVehicle()==b,p.getVehicle()==b?1:0,"native boarding observed="+(p.getVehicle()==b));
 }
 /** null means healthy ongoing propulsion. */
 public static PlayerInteractions.Result sail(CompanionEntity p,BlockPos target){
  if(!(p.getVehicle() instanceof Boat boat)||boat.getControllingPassenger()!=p||!boat.isAlive())return new PlayerInteractions.Result(false,0,"NOT_DRIVING_BOAT");
  if(target==null||!p.level().hasChunkAt(target))return new PlayerInteractions.Result(false,0,"SAIL_DESTINATION_UNKNOWN");
  var delta=Vec3.atCenterOf(target).subtract(boat.position());
  if(delta.horizontalDistanceSqr()<4){boat.setInput(false,false,false,false);return new PlayerInteractions.Result(true,1,"boat destination physically reached; disembark separately");}
  if(delta.lengthSqr()>256*256)return new PlayerInteractions.Result(false,0,"SAIL_SEGMENT_LIMIT_256");
  double angle=net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(delta.z,delta.x))-90-boat.getYRot());
  var ahead=boat.position().add(delta.normalize().scale(2));var next=BlockPos.containing(ahead);
  if(!p.level().hasChunkAt(next)||!(p.level().getFluidState(next).is(FluidTags.WATER)||p.level().getFluidState(next.below()).is(FluidTags.WATER))){boat.setInput(false,false,false,false);return new PlayerInteractions.Result(false,0,"SAIL_ROUTE_BLOCKED: choose another water waypoint");}
  boat.setInput(angle < -5,angle>5,Math.abs(angle)<55,false);return null;
 }
 public static PlayerInteractions.Result disembark(CompanionEntity p,BlockPos shore){
  if(!(p.getVehicle() instanceof Boat boat))return new PlayerInteractions.Result(false,0,"NOT_ON_BOAT");
  if(shore==null||shore.distToCenterSqr(p.position())>9||!TravelTerrain.standable(p,shore))return new PlayerInteractions.Result(false,0,"SAFE_SHORE_NOT_WITHIN_REACH");
  // Vanilla chooses the actual dismount position; requested shore is a safety precondition.
  var landing=boat.getDismountLocationForPassenger(p);
  if(!TravelTerrain.standable(p,BlockPos.containing(landing)))return new PlayerInteractions.Result(false,0,"VANILLA_DISMOUNT_NOT_SAFE: approach shoreline");
  p.stopRiding();return new PlayerInteractions.Result(!p.isPassenger(),1,"native dismount position="+p.blockPosition());
 }
}
