package io.github.yufeiyufei888.hearthcrew.backend.numen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;

/** Read without opening menus, moving the body, generating loot or changing inventory. */
final class FacilityInspection {
    static Map<String,Object> observe(ServerPlayer body,BlockPos p) {
        var level=body.serverLevel();if(!body.server.isSameThread())throw new IllegalStateException("Server thread required");
        var result=new LinkedHashMap<String,Object>();result.put("position",Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ()));
        result.put("observedAt",level.getGameTime());
        var denial=FacilityAccess.denial(level,p);if(!denial.isEmpty())return state(result,denial);
        if(body.getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>4.5*4.5)return state(result,"FACILITY_OUT_OF_REACH");
        var ray=level.clip(new net.minecraft.world.level.ClipContext(body.getEyePosition(),Vec3.atCenterOf(p),
            net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,body));
        if(ray.getType()!=HitResult.Type.BLOCK||!ray.getBlockPos().equals(p))return state(result,"FACILITY_OBSCURED");
        var block=level.getBlockState(p);result.put("block",BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
        var entity=level.getBlockEntity(p);if(!(entity instanceof Container container))return state(result,"PUBLIC_WORKSTATION");
        var parts=new ArrayList<BlockPos>();parts.add(p);
        if(block.getBlock() instanceof ChestBlock&&block.getValue(ChestBlock.TYPE)!=ChestType.SINGLE)parts.add(p.relative(ChestBlock.getConnectedDirection(block)));
        // Container.getItem may unpack an unopened loot table. A query must not do that.
        for(var part:parts){var be=level.getBlockEntity(part);if(be==null)return state(result,"FACILITY_CHANGED");
            if(be.saveWithoutMetadata(level.registryAccess()).contains("LootTable"))return state(result,"LOOT_NOT_GENERATED");}
        if(block.getBlock() instanceof ChestBlock chest){
            var combined=ChestBlock.getContainer(chest,block,level,p,false);
            if(combined==null)return state(result,"CONTAINER_BLOCKED");container=combined;
        }
        var slots=new ArrayList<Map<String,Object>>();
        for(int i=0;i<container.getContainerSize();i++){
            var stack=container.getItem(i);var row=new LinkedHashMap<String,Object>();row.put("slot",i);row.put("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.put("count",stack.getCount());
            if(stack.isDamageableItem())row.put("remainingDurability",stack.getMaxDamage()-stack.getDamageValue());slots.add(row);
        }
        result.put("slots",slots);result.put("state","observed");result.put("inventoryVersion",level.getGameTime()+":"+Integer.toUnsignedString(slots.hashCode()));
        if(entity instanceof AbstractFurnaceBlockEntity){var tag=entity.saveWithoutMetadata(level.registryAccess());
            result.put("processing",Map.of("burnRemaining",tag.getShort("BurnTime"),"cookProgress",tag.getShort("CookTime"),"cookTotal",tag.getShort("CookTimeTotal"),"outputIsActual",true));}
        var order=FurnaceWork.get(level.getServer()).at(level,p);if(order!=null)result.put("order",FurnaceWork.facts(order));
        return result;
    }
    private static Map<String,Object> state(Map<String,Object> result,String state){result.put("state",state);return result;}
}
