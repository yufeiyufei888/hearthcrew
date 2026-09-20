package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.CraftingSelection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

/** Only the synchronous adapter scope is restricted; upstream callers remain unchanged. */
@Mixin(value=CraftOps.class,remap=false)
public abstract class OwnedCraftingSelectionMixin {
    @Redirect(method="candidatesFor",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/crafting/RecipeManager;getAllRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;)Ljava/util/List;"))
    private static List<RecipeHolder<CraftingRecipe>> selectedRecipe(RecipeManager manager,RecipeType<CraftingRecipe> type) {
        return CraftingSelection.recipes(manager.getAllRecipesFor(type));
    }
    @Inject(method="poolOf",at=@At("RETURN"),cancellable=true)
    private static void selectedMaterials(AbstractContainerMenu menu,NumenPlayer body,CallbackInfoReturnable<Map<Item,Integer>> ci) {
        ci.setReturnValue(CraftingSelection.pool(ci.getReturnValue()));
    }
}
