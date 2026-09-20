package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import java.util.Set;

/** Count means new verified items, unlike the legacy MINE block-count contract. */
public final class CollectRequest extends PrepareRequest {
    final Set<Block> targets;
    final boolean allowPreparation;
    public CollectRequest(String id,long deadline,Item output,int count,Set<Block> targets,boolean allowPreparation) {
        super("hearthcrew_collect_resource",id,deadline,output,count,0);
        if(targets==null||targets.isEmpty()||targets.size()>32)throw new IllegalArgumentException("collection source scope");
        this.targets=Set.copyOf(targets);this.allowPreparation=allowPreparation;
    }
}
