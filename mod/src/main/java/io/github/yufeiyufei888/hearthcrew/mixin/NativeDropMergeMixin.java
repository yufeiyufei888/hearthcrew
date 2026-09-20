package io.github.yufeiyufei888.hearthcrew.mixin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemEntity.class)
public class NativeDropMergeMixin {
 @Unique private static final ThreadLocal<Integer> hearthcrew$before=ThreadLocal.withInitial(()->0);
 @Inject(method="merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V",at=@At("HEAD"))
 private static void before(ItemEntity target,ItemStack targetStack,ItemEntity source,ItemStack sourceStack,CallbackInfo ci){
  hearthcrew$before.set(sourceStack.getCount());
 }
 // Transfer evidence after the stack mutation but before vanilla discards an empty source.
 @Inject(method="merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/item/ItemEntity;merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",shift=At.Shift.AFTER))
 private static void after(ItemEntity target,ItemStack targetStack,ItemEntity source,ItemStack sourceStack,CallbackInfo ci){
  int moved=hearthcrew$before.get()-sourceStack.getCount();hearthcrew$before.remove();if(source.level().isClientSide())return;
  for(var p:CrewWorldData.liveCompanions(source.getServer())){if(moved<=0)break;moved-=p.executor().nativeDropMerged(source.getUUID(),target.getUUID(),moved);}
 }
}
