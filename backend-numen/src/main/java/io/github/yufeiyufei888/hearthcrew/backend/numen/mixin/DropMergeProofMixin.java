package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import io.github.yufeiyufei888.hearthcrew.backend.numen.DropProof;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe vanilla's merge transaction. Does not modify the merge or item stacks. */
@Mixin(ItemEntity.class)
public class DropMergeProofMixin {
    @Unique private static final ThreadLocal<Integer> hearthcrew$sourceCount=new ThreadLocal<>();
    @Inject(method="merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V",at=@At("HEAD"),require=1)
    private static void hearthcrew$beforeMerge(ItemEntity target,ItemStack targetStack,ItemEntity source,ItemStack sourceStack,CallbackInfo ci) {
        hearthcrew$sourceCount.set(sourceStack.getCount());
    }
    @Inject(method="merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/item/ItemEntity;merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",shift=At.Shift.AFTER),require=1)
    private static void hearthcrew$afterMerge(ItemEntity target,ItemStack targetStack,ItemEntity source,ItemStack sourceStack,CallbackInfo ci) {
        var before=hearthcrew$sourceCount.get();hearthcrew$sourceCount.remove();
        if(before!=null&&!source.level().isClientSide())DropProof.merged(source.getServer(),source.getUUID(),target.getUUID(),targetStack.getItem(),before-sourceStack.getCount(),before);
    }
}
