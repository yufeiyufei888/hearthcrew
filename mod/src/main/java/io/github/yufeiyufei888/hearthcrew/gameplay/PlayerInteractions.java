package io.github.yufeiyufei888.hearthcrew.gameplay;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Native player interaction adapters. Called only by the body's active executor. */
public final class PlayerInteractions {
 public record Result(boolean completed,int changed,String reason){}
 public static int select(CompanionEntity p,ResourceLocation item){
  if(item==null)return p.selectedSlot();
  for(int i=0;i<36;i++)if(!p.inventory().getItem(i).isEmpty()&&BuiltInRegistries.ITEM.getKey(p.inventory().getItem(i).getItem()).equals(item)){p.selectSlot(i);return p.selectedSlot();}
  throw new IllegalArgumentException("MISSING_ITEM: "+item);
 }
 public static BlockHitResult placementFace(CompanionEntity p,BlockPos target){return placementFaceFrom(p,target,p.getEyePosition());}
 public static BlockHitResult placementFaceFrom(CompanionEntity p,BlockPos target,Vec3 eye){
  for(var direction:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)){
   var support=target.relative(direction);if(!p.level().hasChunkAt(support))continue;
   if(p.level().getBlockState(support).getCollisionShape(p.level(),support).isEmpty())continue;
   var face=direction.getOpposite();var at=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
   if(eye.distanceToSqr(at)>20.25)continue;
   var clip=p.level().clip(new net.minecraft.world.level.ClipContext(eye,at,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,p));
   if(clip.getType()==HitResult.Type.BLOCK&&!clip.getBlockPos().equals(support))continue;
   return new BlockHitResult(at,face,support,false);
  }return null;
 }
 public static InteractionResult place(CompanionEntity p,BlockPos target){
  var hit=placementFace(p,target);if(hit==null)throw new IllegalArgumentException("NO_CLICK_FACE: support missing or obscured");
  var item=p.getMainHandItem();if(!(item.getItem() instanceof BlockItem bi))throw new IllegalArgumentException("held item is not a block");
  if(bi.getBlock() instanceof DoorBlock||bi.getBlock() instanceof BedBlock){
   BlockPos second=bi.getBlock() instanceof DoorBlock?target.above():target.relative(p.getDirection());
   if(!p.level().getBlockState(second).canBeReplaced()||CrewWorldData.get(p.server).playerBlock(p.level(),second))throw new IllegalArgumentException("MULTIBLOCK_OCCUPIED_OR_PROTECTED: "+second);
  }
  var context=new net.minecraft.world.item.context.BlockPlaceContext(p,InteractionHand.MAIN_HAND,item,hit);
  var placed=((io.github.yufeiyufei888.hearthcrew.mixin.BlockPlacementAccessor)bi).hearthcrew$placementState(context);
  if(placed==null||!context.canPlace()||!placed.canSurvive(p.level(),target))throw new IllegalArgumentException("PLACEMENT_REQUIREMENTS: direction, support or occupied multiblock");
  if(!p.level().isUnobstructed(placed,target,net.minecraft.world.phys.shapes.CollisionContext.of(p)))throw new IllegalArgumentException("PLACEMENT_ENTITY_COLLISION: choose another stance or wait for teammate");
  boolean sneaking=p.isShiftKeyDown();p.setShiftKeyDown(true);
  try{return p.gameMode.useItemOn(p,p.level(),item,InteractionHand.MAIN_HAND,hit);}finally{p.setShiftKeyDown(sneaking);}
 }
 public static Result equip(CompanionEntity p,ResourceLocation resource){
  select(p,resource);var held=p.getMainHandItem();var slot=held.getItem() instanceof ShieldItem?EquipmentSlot.OFFHAND:p.getEquipmentSlotForItem(held);
  if(slot==EquipmentSlot.MAINHAND)return new Result(true,0,"main-hand selection verified");
  var previous=p.getItemBySlot(slot);p.setItemSlot(slot,held);p.setItemInHand(InteractionHand.MAIN_HAND,previous);
  return new Result(p.getItemBySlot(slot)==held,1,"equipped "+slot.getName());
 }
 public static boolean shared(CompanionEntity p,BlockPos pos){
  var data=CrewWorldData.get(p.server);var owner=data.placementOwner(p.level(),pos);
  return owner!=null&&owner.startsWith("crew:")||p.level().getBlockEntity(pos)!=null&&p.level().getBlockEntity(pos).getPersistentData().getBoolean("HearthCrewPublic");
 }
 public static Result transferContainer(CompanionEntity p,BlockPos pos,ResourceLocation resource,int amount,boolean take){
  return transferContainer(p,pos,resource,amount,take,false);
 }
 static Result transferContainer(CompanionEntity p,BlockPos pos,ResourceLocation resource,int amount,boolean take,boolean orderCollection){
  if(amount<1||amount>2304)return new Result(false,0,"INVALID_AMOUNT");
  var block=p.level().getBlockEntity(pos);
  if(block!=null&&block.getPersistentData().contains("HearthCrewOrder")){
   var order=block.getPersistentData().getCompound("HearthCrewOrder");
   if(!orderCollection||!take||!order.hasUUID("owner")||!order.getUUID("owner").equals(p.getUUID())||!resource.toString().equals(order.getString("output")))return new Result(false,0,"WORKSTATION_RESERVED: use COLLECT_PROCESS for the owning order");
  }
  if(!shared(p,pos))return new Result(false,0,"PRIVATE_CONTAINER: owner must mark public");
  if(!TargetInspection.sight(p,p.getEyePosition(),pos))return new Result(false,0,"CONTAINER_OBSCURED_OR_OUT_OF_REACH");
  var state=p.level().getBlockState(pos);var provider=state.getMenuProvider(p.level(),pos);
  if(provider==null||p.openMenu(provider).isEmpty())return new Result(false,0,"CONTAINER_CANNOT_OPEN");
  int moved=0;var menu=p.containerMenu;
  try{
   if(!menu.stillValid(p))return new Result(false,0,"CONTAINER_INVALID");
   for(var slot:menu.slots){
    if(slot.container==p.getInventory())continue;
    if(take){var stack=slot.getItem();if(stack.isEmpty()||!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(resource)||!slot.mayPickup(p))continue;
     int n=Math.min(amount-moved,stack.getCount());var remainder=p.inventory().addItem(stack.copyWithCount(n));n-=remainder.getCount();
     if(n>0){var removed=slot.remove(n);slot.onTake(p,removed);moved+=n;}
    }else{
     for(int i=0;i<36&&moved<amount;i++){var source=p.inventory().getItem(i);if(source.isEmpty()||!BuiltInRegistries.ITEM.getKey(source.getItem()).equals(resource)||!slot.mayPlace(source))continue;
      var at=slot.getItem();if(!at.isEmpty()&&!ItemStack.isSameItemSameComponents(at,source))continue;
      int n=Math.min(Math.min(source.getCount(),amount-moved),slot.getMaxStackSize(source)-at.getCount());if(n<=0)continue;
      if(at.isEmpty())slot.set(source.split(n));else{at.grow(n);source.shrink(n);slot.setChanged();}moved+=n;
     }
    }
    if(moved>=amount)break;
   }p.getInventory().setChanged();menu.broadcastChanges();
   return new Result(moved==amount,moved,moved==amount?"actual slot transfer verified":"PARTIAL: missing items or legal slot capacity");
  }finally{p.closeContainer();}
 }
 public static Result interact(CompanionEntity p,BlockPos pos,UUID target,ResourceLocation resource){
  select(p,resource);
  if(pos!=null){
   if(CrewWorldData.get(p.server).playerBlock(p.level(),pos)&&!shared(p,pos))return new Result(false,0,"PROTECTED_TARGET");
   if(!TargetInspection.sight(p,p.getEyePosition(),pos))return new Result(false,0,"INTERACTION_NOT_REACHABLE");
   var before=p.level().getBlockState(pos);int count=p.getMainHandItem().getCount();int damage=p.getMainHandItem().getDamageValue();
   var ray=p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(),Vec3.atCenterOf(pos),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,p));
   p.gameMode.useItemOn(p,p.level(),p.getMainHandItem(),InteractionHand.MAIN_HAND,ray);
   boolean changed=!before.equals(p.level().getBlockState(pos))||count!=p.getMainHandItem().getCount()||damage!=p.getMainHandItem().getDamageValue()||p.containerMenu!=p.inventoryMenu;
   if(p.containerMenu!=p.inventoryMenu)p.closeContainer();
   return new Result(changed,changed?1:0,changed?"native interaction state changed":"NO_OBSERVED_EFFECT");
  }
  var entity=target==null?null:p.serverLevel().getEntity(target);
  if(entity==null||p.distanceToSqr(entity)>9||!p.hasLineOfSight(entity))return new Result(false,0,"ENTITY_NOT_REACHABLE");
  var before=new net.minecraft.nbt.CompoundTag();entity.saveWithoutId(before);var held=p.getMainHandItem().copy();
  p.interactOn(entity,InteractionHand.MAIN_HAND);var after=new net.minecraft.nbt.CompoundTag();entity.saveWithoutId(after);
  boolean changed=!before.equals(after)||!ItemStack.matches(held,p.getMainHandItem())||p.getVehicle()==entity;
  return new Result(changed,changed?1:0,changed?"entity/item postcondition observed":"NO_OBSERVED_EFFECT");
 }
}
