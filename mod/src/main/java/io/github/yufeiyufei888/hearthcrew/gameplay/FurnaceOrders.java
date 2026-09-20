package io.github.yufeiyufei888.hearthcrew.gameplay;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
/** Persistent order metadata stays on its real furnace; vanilla alone advances cooking. */
public final class FurnaceOrders {
 public static PlayerInteractions.Result start(CompanionEntity p,BlockPos pos,ResourceLocation item,int count,String action){
  if(!(p.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace))return new PlayerInteractions.Result(false,0,"NOT_A_FURNACE");
  if(!PlayerInteractions.shared(p,pos)||!TargetInspection.sight(p,p.getEyePosition(),pos))return new PlayerInteractions.Result(false,0,"PRIVATE_OR_OBSCURED_FURNACE");
  if(furnace.getPersistentData().contains("HearthCrewOrder"))return new PlayerInteractions.Result(false,0,"WORKSTATION_RESERVED: collect or reconcile existing order");
  if(!furnace.getItem(0).isEmpty()||!furnace.getItem(1).isEmpty()||!furnace.getItem(2).isEmpty())return new PlayerInteractions.Result(false,0,"FURNACE_NOT_EMPTY: existing resources need explicit handling");
  RecipeType<? extends AbstractCookingRecipe> type=furnace instanceof SmokerBlockEntity?RecipeType.SMOKING:furnace instanceof BlastFurnaceBlockEntity?RecipeType.BLASTING:RecipeType.SMELTING;
  int source=-1;for(int i=0;i<36;i++)if(BuiltInRegistries.ITEM.getKey(p.inventory().getItem(i).getItem()).equals(item)&&p.inventory().getItem(i).getCount()>=count){source=i;break;}
  if(source<0||count<1||count>64)return new PlayerInteractions.Result(false,0,"MISSING_INPUT: one batch stack of 1..64 required");
  var input=p.inventory().getItem(source);var recipe=p.level().getRecipeManager().getRecipeFor(type,new SingleRecipeInput(input),p.level());
  if(recipe.isEmpty())return new PlayerInteractions.Result(false,0,"NO_RECIPE_FOR_STATION");
  int fuel=-1,fuelCount=0;int needed=recipe.get().value().getCookingTime()*count;
  for(int i=0;i<36;i++){if(i==source)continue;var s=p.inventory().getItem(i);int duration=s.getBurnTime(type);if(duration<=0)continue;int n=(needed+duration-1)/duration;if(n<=s.getCount()&&n<=64){fuel=i;fuelCount=n;break;}}
  if(fuel<0)return new PlayerInteractions.Result(false,0,"MISSING_FUEL: enough real fuel for requested batch required");
  var output=recipe.get().value().getResultItem(p.registryAccess());if(output.getCount()*count>output.getMaxStackSize())return new PlayerInteractions.Result(false,0,"OUTPUT_CAPACITY_EXCEEDED");
  // Preflight complete. Single server-thread commit, no recipes or ticks simulated.
  furnace.setItem(0,p.inventory().removeItem(source,count));furnace.setItem(1,p.inventory().removeItem(fuel,fuelCount));
  var order=new CompoundTag();order.putUUID("owner",p.getUUID());order.putString("action",action);order.putString("output",BuiltInRegistries.ITEM.getKey(output.getItem()).toString());order.putInt("expected",count*output.getCount());order.putInt("collected",0);order.putLong("started",p.level().getGameTime());
  furnace.getPersistentData().put("HearthCrewOrder",order);furnace.setChanged();p.getInventory().setChanged();
  return new PlayerInteractions.Result(true,count,"PROCESS_SUBMITTED: materials deposited; output is not yet produced");
 }
 public static PlayerInteractions.Result collect(CompanionEntity p,BlockPos pos){
  if(!(p.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace))return new PlayerInteractions.Result(false,0,"WORKSTATION_MISSING: do not recreate inputs");
  var order=furnace.getPersistentData().getCompound("HearthCrewOrder");
  if(!order.hasUUID("owner")||!order.getUUID("owner").equals(p.getUUID()))return new PlayerInteractions.Result(false,0,"ORDER_NOT_OWNED");
  int remaining=order.getInt("expected")-order.getInt("collected");
  if(furnace.getItem(2).getCount()<remaining)return new PlayerInteractions.Result(false,0,"WAITING_PROCESS: actual output not ready");
  var result=PlayerInteractions.transferContainer(p,pos,ResourceLocation.parse(order.getString("output")),remaining,true,true);
  order.putInt("collected",order.getInt("collected")+result.changed());
  if(order.getInt("collected")>=order.getInt("expected"))furnace.getPersistentData().remove("HearthCrewOrder");else furnace.getPersistentData().put("HearthCrewOrder",order);
  furnace.setChanged();return result;
 }
 public static Map<String,Object> observe(CompanionEntity p,BlockPos pos){
  var block=p.level().getBlockEntity(pos);if(!(block instanceof net.minecraft.world.Container c))return Map.of("state","not_container");
  if(!PlayerInteractions.shared(p,pos))return Map.of("state","private");
  var slots=new ArrayList<Map<String,Object>>();for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);slots.add(Map.of("slot",i,"item",BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),"count",s.getCount()));}
  var result=new LinkedHashMap<String,Object>();result.put("slots",slots);result.put("state","observed");
  if(block.getPersistentData().contains("HearthCrewOrder")){var order=block.getPersistentData().getCompound("HearthCrewOrder");result.put("order",Map.of("owner",order.getUUID("owner").toString(),"output",order.getString("output"),"expected",order.getInt("expected"),"collected",order.getInt("collected")));}return result;
 }
}
