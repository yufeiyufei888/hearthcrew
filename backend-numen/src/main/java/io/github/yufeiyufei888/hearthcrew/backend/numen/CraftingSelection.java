package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;

/** Restricts native menu crafting to the recipe/material allocation already selected by the plan. */
public final class CraftingSelection implements AutoCloseable {
    private static final ThreadLocal<CraftingSelection> ACTIVE=new ThreadLocal<>();
    private final String recipe;
    private final NumenPlayer body;
    private final Map<Item,Integer> allowance=new HashMap<>(),before=new HashMap<>();
    CraftingSelection(NumenPlayer body,String recipe,Map<String,Integer> inputs) {
        if(ACTIVE.get()!=null)throw new IllegalStateException("Nested crafting selection");
        this.body=body;this.recipe=recipe;
        inputs.forEach((id,count)->{
            var item=BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
            if(item==net.minecraft.world.item.Items.AIR||count<1)throw new IllegalArgumentException("Invalid selected material");
            allowance.put(item,count);before.put(item,body.getInventory().countItem(item));
        });
        ACTIVE.set(this);
    }
    public static <T extends Recipe<?>> List<RecipeHolder<T>> recipes(List<RecipeHolder<T>> candidates) {
        var current=ACTIVE.get();if(current==null)return candidates;
        return candidates.stream().filter(r->r.id().toString().equals(current.recipe)).toList();
    }
    public static Map<Item,Integer> pool(Map<Item,Integer> actual) {
        var current=ACTIVE.get();if(current==null)return actual;
        var selected=new HashMap<Item,Integer>();
        current.allowance.forEach((item,budget)->{
            int spent=Math.max(0,current.before.get(item)-current.body.getInventory().countItem(item));
            int amount=Math.min(actual.getOrDefault(item,0),Math.max(0,budget-spent));
            if(amount>0)selected.put(item,amount);
        });return selected;
    }
    @Override public void close(){if(ACTIVE.get()!=this)throw new IllegalStateException("Crafting scope changed");ACTIVE.remove();}
}
