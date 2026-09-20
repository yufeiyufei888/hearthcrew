package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/** Observe the vanilla inventory transaction, before ItemEntity discards its empty stack. */
public final class PickupEvents {
    public static void acquired(ItemEntityPickupEvent.Post event) {
        var player=event.getPlayer();
        if(player.getServer()==null)return;
        var original=event.getOriginalStack();
        int amount=original.getCount()-event.getCurrentStack().getCount();
        if(amount<=0)return;
        var resource=BuiltInRegistries.ITEM.getKey(original.getItem());
        var id=event.getItemEntity().getUUID();
        if(player instanceof CompanionEntity companion)companion.executor().nativePickup(id,amount,resource);
        else for(var companion:CrewWorldData.liveCompanions(player.getServer()))
            if(amount>0&&companion.level()==player.level())amount-=companion.executor().observedOtherPickup(id,amount,resource,player.getUUID());
    }
}
