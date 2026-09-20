package io.github.yufeiyufei888.hearthcrew.gameplay;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
/** Read-only block and tool facts, not mineral/resource-list inference. */
public final class TargetInspection {
 private TargetInspection(){}
 public static int bestTool(CompanionEntity b,BlockState state){
  var candidates=new ArrayList<io.github.yufeiyufei888.hearthcrew.kernel.HarvestChoice.Candidate>();
  for(int i=0;i<36;i++){var item=b.inventory().getItem(i);candidates.add(new io.github.yufeiyufei888.hearthcrew.kernel.HarvestChoice.Candidate(i,item.isCorrectToolForDrops(state),item.getDestroySpeed(state),item.isDamageableItem()?item.getMaxDamage()-item.getDamageValue():Integer.MAX_VALUE));}
  return io.github.yufeiyufei888.hearthcrew.kernel.HarvestChoice.choose(state.requiresCorrectToolForDrops(),candidates);
 }
 public static List<Map<String,Object>> tools(CompanionEntity b){var result=new ArrayList<Map<String,Object>>();for(int i=0;i<36;i++){
  var s=b.inventory().getItem(i);if(s.isEmpty())continue;result.add(Map.of("slot",i,"item",BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),"count",s.getCount(),"selected",i==b.selectedSlot(),"remainingDurability",s.isDamageableItem()?s.getMaxDamage()-s.getDamageValue():-1));}return result;}
 public static List<String> suitablePickaxes(BlockState state){return List.of(Items.WOODEN_PICKAXE,Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE,Items.NETHERITE_PICKAXE).stream().filter(i->new ItemStack(i).isCorrectToolForDrops(state)).map(i->BuiltInRegistries.ITEM.getKey(i).toString()).toList();}
 public static String missingTool(BlockState state){return "MISSING_HARVEST_TOOL: full inventory has no qualified tool; suitablePickaxes="+suitablePickaxes(state)+"; query observe.recipes for suitable tools, inspect own materials/station, prepare locally within current stage before requesting teammate supplies";}
 public static boolean sight(CompanionEntity b,Vec3 eye,BlockPos target){
  var center=Vec3.atCenterOf(target);var points=new ArrayList<Vec3>();points.add(center);
  for(var direction:Direction.values())points.add(center.add(Vec3.atLowerCornerOf(direction.getNormal()).scale(.49)));
  for(var point:points){if(eye.distanceToSqr(point)>16)continue;var ray=b.level().clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,b));if(ray.getType()==HitResult.Type.MISS||ray.getBlockPos().equals(target))return true;}return false;
 }
 public static List<BlockPos> stances(CompanionEntity b,BlockPos target){
  var found=new ArrayList<BlockPos>();for(var mutable:BlockPos.betweenClosed(target.offset(-4,-3,-4),target.offset(4,3,4))){var p=mutable.immutable();
   if(p.distSqr(b.blockPosition())>1024||!TravelTerrain.standable(b,p))continue;
   if(TravelTerrain.feetPoint(b,p).add(0,b.getEyeHeight(),0).distanceToSqr(Vec3.atCenterOf(target))<12.25&&sight(b,TravelTerrain.feetPoint(b,p).add(0,b.getEyeHeight(),0),target))found.add(p);
  }found.sort(Comparator.comparingDouble(p->p.distToCenterSqr(b.position())));return found;
 }
 public static BlockPos accessibleStance(CompanionEntity b,BlockPos target){
  var route=b.getNavigation().pathToAny("interaction:"+target.asLong(),stances(b,target));return route==null?null:route.destination();
 }
 public static Map<String,Object> inspect(CompanionEntity b,BlockPos p){
  if(p.distSqr(b.blockPosition())>1024||!b.level().hasChunkAt(p))return Map.of("position",TravelTerrain.position(p),"state","unobserved_outside_loaded_bound");
  var state=b.level().getBlockState(p);var stands=stances(b,p);BlockPos reachable=null;for(var s:stands.stream().limit(8).toList()){var path=b.getNavigation().createPath(s,0);if(path!=null&&path.canReach()){reachable=s;break;}}
  var result=new LinkedHashMap<String,Object>();result.put("position",TravelTerrain.position(p));result.put("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());result.put("air",state.isAir());result.put("fluid",!b.level().getFluidState(p).isEmpty());
  result.put("lineOfSight",sight(b,b.getEyePosition(),p));result.put("stanceCandidates",stands.stream().limit(12).map(TravelTerrain::position).toList());
  var pathStates=stands.stream().limit(8).map(b.getNavigation()::pathStatus).toList();
  result.put("path",reachable!=null?"reachable":pathStates.contains("searching")?"searching":pathStates.contains("budget_exhausted")?"budget_exhausted":stands.isEmpty()?"no_stance_candidate":"blocked_for_checked_candidates");
  result.put("pathCandidateStates",pathStates);if(reachable!=null)result.put("reachableStance",TravelTerrain.position(reachable));
  result.put("dynamicBlockers",stands.stream().limit(8).flatMap(p0->TravelTerrain.blockers(b,TravelTerrain.feetPoint(b,p0)).stream()).distinct().map(Object::toString).toList());result.put("pathSearchLimit",8);result.put("candidateIsNotReachabilityProof",true);
  result.put("supportBelow",b.level().getBlockState(p.below()).isFaceSturdy(b.level(),p.below(),Direction.UP));
  result.put("protection",state.hasBlockEntity()?"block_entity":CrewWorldData.get(b.getServer()).protectionReason(b.level(),p));
  result.put("requiresCorrectTool",state.requiresCorrectToolForDrops());int best=bestTool(b,state);result.put("bestToolSlot",best);result.put("toolAvailable",best>=0);result.put("suitablePickaxes",suitablePickaxes(state));result.put("toolPreparation","query real recipes; gather missing materials and craft within the current stage; ask teammates only when needed");return result;
 }
 public static boolean excavationBlock(CompanionEntity b,BlockPos p){
  if(!b.level().hasChunkAt(p)||!b.level().getWorldBorder().isWithinBounds(p))return false;var s=b.level().getBlockState(p);
  if(!b.level().getFluidState(p).isEmpty()||TravelTerrain.dangerous(b,p)||s.hasBlockEntity()||CrewWorldData.get(b.getServer()).playerBlock(b.level(),p))return false;
  if(s.isAir())return true;
  // Deliberately narrow natural terrain; unknown structures, ores and falling blocks are not tunnelling material.
  return Set.of(Blocks.STONE,Blocks.DEEPSLATE,Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.COARSE_DIRT,Blocks.ROOTED_DIRT,Blocks.GRANITE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.CALCITE).contains(s.getBlock())&&bestTool(b,s)>=0;
 }
}
