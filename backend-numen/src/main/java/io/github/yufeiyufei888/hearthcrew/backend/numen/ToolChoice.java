package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.moves.ToolSet;
import net.minecraft.world.level.block.state.BlockState;

/** Actual stock: an equally effective replacement wins over a nearly broken tool. */
public final class ToolChoice {
    public static void hold(NumenPlayer body,BlockState state){
        int selected=-1,durability=-1;boolean qualified=false;double speed=-1;var inv=body.getInventory();
        for(int slot=0;slot<inv.getContainerSize();slot++){
            var stack=inv.getItem(slot);if(stack.isEmpty()||stack.getDestroySpeed(state)<=1)continue;
            int remaining=stack.isDamageableItem()?stack.getMaxDamage()-stack.getDamageValue():Integer.MAX_VALUE;if(remaining<=0)continue;
            boolean canHarvest=!state.requiresCorrectToolForDrops()||stack.isCorrectToolForDrops(state);double rate=ToolSet.calculateSpeedVsBlock(stack,state);
            if(selected<0||canHarvest&&!qualified||canHarvest==qualified&&(rate>speed||rate==speed&&remaining>durability)){
                selected=slot;qualified=canHarvest;speed=rate;durability=remaining;
            }
        }
        if(selected>=0)body.holdInHand(selected);
    }
}
