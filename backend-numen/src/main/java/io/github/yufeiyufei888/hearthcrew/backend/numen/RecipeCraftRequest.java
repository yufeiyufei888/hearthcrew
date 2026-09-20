package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.*;

/** Exact recipe and execution count; existing output never satisfies a new craft request. */
final class RecipeCraftRequest extends PrepareRequest {
    final ResourceLocation recipe;
    final int executions;
    final boolean allowPreparation;
    final BlockPos workstation;
    private RecipeCraftRequest(String id,long deadline,ResourceLocation recipe,int executions,Item output,int count,boolean prepare,BlockPos workstation) {
        super("hearthcrew_recipe_craft",id,deadline,output,count,0);
        this.recipe=recipe;this.executions=executions;allowPreparation=prepare;
        this.workstation=workstation==null?null:workstation.immutable();
    }
    static RecipeCraftRequest resolve(ServerLevel level,String id,long deadline,ResourceLocation recipe,int executions,boolean prepare,BlockPos workstation) {
        if(recipe==null||executions<1||executions>64)throw new IllegalArgumentException("CRAFT requires recipe resource and count 1..64 executions");
        var holder=level.getRecipeManager().byKey(recipe).orElseThrow(()->new IllegalArgumentException("RECIPE_NOT_LOADED:"+recipe));
        var value=holder.value();
        if(!(value instanceof ShapedRecipe||value instanceof ShapelessRecipe)||value.isSpecial())throw new IllegalArgumentException("RECIPE_ADAPTER_UNAVAILABLE:"+recipe);
        var output=value.getResultItem(level.registryAccess());
        int quantity=Math.multiplyExact(output.getCount(),executions);
        if(output.isEmpty()||quantity>64)throw new IllegalArgumentException("CRAFT output batch exceeds 64 items; split recipe executions");
        return new RecipeCraftRequest(id,deadline,recipe,executions,output.getItem(),quantity,prepare,workstation);
    }
    String goal(){return "craft-result:"+recipe;}
}
