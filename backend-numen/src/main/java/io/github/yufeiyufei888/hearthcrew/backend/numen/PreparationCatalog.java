package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.kernel.preparation.PreparationGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Server-thread recipe snapshot. Recipes do not implicitly grant resource gathering capabilities. */
final class PreparationCatalog {
    static final String TABLE="facility:crafting_table";
    enum Kind { CRAFT, APPROACH, PLACE, ACQUIRE }
    record Operation(Kind kind,Item item,BlockPos position,String recipe,SourceProbe.Source source) {
        Operation(Kind kind,Item item,BlockPos position,String recipe){this(kind,item,position,recipe,null);}
    }
    final Map<String,Operation> operations=new LinkedHashMap<>();
    final List<PreparationGraph.Method> methods=new ArrayList<>();
    final Map<String,Integer> stock=new HashMap<>();
    final Set<String> unavailable=new LinkedHashSet<>();
    static String key(Item item){return BuiltInRegistries.ITEM.getKey(item).toString();}
    static int eligible(NumenPlayer body,Item item,int minimum) {
        int total=0;
        for(var stack:body.getInventory().items)if(stack.is(item)&&(!stack.isDamageableItem()||stack.getMaxDamage()-stack.getDamageValue()>=minimum))total+=stack.getCount();
        return total;
    }
    PreparationCatalog(NumenPlayer body,PrepareRequest request,BlockPos reachableTable,boolean currentlyReachable,BlockPos ownTableSite,boolean tableSearchUnknown) {
        long began=System.nanoTime();
        for(var stack:body.getInventory().items)if(!stack.isEmpty()&&(!(stack.getItem() instanceof PickaxeItem)||stack.getMaxDamage()-stack.getDamageValue()>=18))stock.merge(key(stack.getItem()),stack.getCount(),Integer::sum);
        stock.put(key(request.output),request instanceof CollectRequest?0:eligible(body,request.output,request.minimumDurability));
        if(currentlyReachable)stock.put(TABLE,1);
        else if(reachableTable!=null)add("approach:table",TABLE,1,List.of(),new PreparationGraph.Cost(0,0,0,20),1,true,
            new Operation(Kind.APPROACH,Items.CRAFTING_TABLE,reachableTable,""));
        else if(tableSearchUnknown)add("unknown:table",TABLE,1,List.of(),new PreparationGraph.Cost(0,0,0,20),1,false,null);
        if(ownTableSite!=null&&(!(request instanceof RecipeCraftRequest r)||r.allowPreparation))add("place:table",TABLE,1,List.of(PreparationGraph.Need.item(key(Items.CRAFTING_TABLE),1)),
            new PreparationGraph.Cost(0,0,1,20),1,true,new Operation(Kind.PLACE,Items.CRAFTING_TABLE,ownTableSite,""));
        for(var holder:body.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if(request instanceof CollectRequest c&&!c.allowPreparation)break;
            var recipe=holder.value();
            if(request instanceof RecipeCraftRequest r&&!r.allowPreparation&&!holder.id().equals(r.recipe))continue;
            // Dynamic/special outputs and custom recipe implementations need their own adapters.
            if(!(recipe instanceof ShapedRecipe||recipe instanceof ShapelessRecipe)||recipe.isSpecial())continue;
            var output=recipe.getResultItem(body.registryAccess());if(output.isEmpty())continue;
            // This collection contract names actual source blocks; do not substitute disassembly.
            if(request instanceof CollectRequest&&output.is(request.output))continue;
            var needs=new ArrayList<PreparationGraph.Need>();boolean supported=true;
            for(var ingredient:recipe.getIngredients())if(!ingredient.isEmpty()) {
                var options=Arrays.stream(ingredient.getItems()).map(ItemStack::getItem).map(PreparationCatalog::key).distinct().sorted().toList();
                if(options.isEmpty()){supported=false;break;}
                // Returned containers/components cannot be represented as ordinary consumed stock.
                if(Arrays.stream(ingredient.getItems()).anyMatch(ItemStack::hasCraftingRemainingItem)){supported=false;break;}
                needs.add(new PreparationGraph.Need(options,1,true));
            }
            if(!supported||needs.isEmpty()){unavailable.add(holder.id().toString());continue;}
            boolean inventoryGrid=recipe instanceof ShapedRecipe shaped?shaped.getWidth()<=2&&shaped.getHeight()<=2:needs.size()<=4;
            // Three equal recipe cells are one material requirement, not three separate mining tasks.
            var grouped=new LinkedHashMap<List<String>,Integer>();
            for(var need:needs)grouped.merge(need.alternatives(),need.count(),Integer::sum);
            needs.clear();grouped.forEach((items,count)->needs.add(new PreparationGraph.Need(items,count,true)));
            if(!inventoryGrid)needs.add(PreparationGraph.Need.condition(TABLE));
            String id="craft:"+holder.id();
            String produced=request instanceof RecipeCraftRequest r&&holder.id().equals(r.recipe)?r.goal():key(output.getItem());
            add(id,produced,output.getCount(),needs,new PreparationGraph.Cost(0,0,0,2),64,true,
                new Operation(Kind.CRAFT,output.getItem(),null,holder.id().toString()));
        }
        SlowOperations.record("preparation_snapshot",began);
    }
    void sources(List<SourceProbe.Source> sources,PrepareRequest request) {
        if(request instanceof RecipeCraftRequest r&&!r.allowPreparation)return;
        for(var source:sources) {
            boolean goal=request instanceof CollectRequest&&source.output()==request.output;
            if(request instanceof CollectRequest c&&(goal&&!c.targets.contains(source.block())||!goal&&!c.allowPreparation))continue;
            var needs=source.tools().isEmpty()?List.<PreparationGraph.Need>of():List.of(new PreparationGraph.Need(source.tools().stream().map(PreparationCatalog::key).toList(),1,false));
            String id="acquire:"+BuiltInRegistries.BLOCK.getKey(source.block());
            add(id,key(source.output()),1,needs,new PreparationGraph.Cost(1,goal?0:1,0,40),Math.min(64,source.positions().size()),true,
                new Operation(Kind.ACQUIRE,source.output(),null,"",source));
        }
    }
    private void add(String id,String output,int quantity,List<PreparationGraph.Need> needs,PreparationGraph.Cost cost,int maximum,boolean verified,Operation operation) {
        methods.add(new PreparationGraph.Method(id,output,quantity,needs,cost,maximum,verified));
        if(operation!=null)operations.put(id,operation);
    }
    PreparationGraph.Result plan(PrepareRequest request) {
        return plan(request,request.count,request.limits.maxSteps(),request.limits.maxBreaks());
    }
    PreparationGraph.Result plan(PrepareRequest request,int needed,int remainingSteps,int remainingBreaks) {
        return compute(List.copyOf(methods),Map.copyOf(stock),operations.entrySet().stream().filter(e->e.getValue().kind()==Kind.ACQUIRE).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()),new PreparationGraph.Limits(request.limits.maxDepth(),Math.max(1,remainingSteps),Math.max(0,remainingBreaks),4096,64),request instanceof RecipeCraftRequest r?r.goal():key(request.output),needed,()->true);
    }
    java.util.concurrent.CompletableFuture<PreparationGraph.Result> planAsync(PrepareRequest request,int needed,int steps,int breaks,SearchBudget budget){
        var snapshotMethods=List.copyOf(methods);var snapshotStock=Map.copyOf(stock);
        var acquisition=operations.entrySet().stream().filter(e->e.getValue().kind()==Kind.ACQUIRE).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var limits=new PreparationGraph.Limits(request.limits.maxDepth(),Math.max(1,steps),Math.max(0,breaks),4096,64);
        String goal=request instanceof RecipeCraftRequest r?r.goal():key(request.output);
        return java.util.concurrent.CompletableFuture.supplyAsync(()->compute(snapshotMethods,snapshotStock,acquisition,limits,goal,needed,budget::claim));
    }
    private static PreparationGraph.Result compute(List<PreparationGraph.Method> methods,Map<String,Integer> stock,Set<String> acquisition,PreparationGraph.Limits limits,String goal,int needed,java.util.function.BooleanSupplier credit){
        long began=System.nanoTime();
        var result=new PreparationGraph(methods,limits,credit).plan(goal,needed,stock);
        SlowOperations.record("preparation_graph_including_yield",began);
        if(result.plan()==null)return result;
        // A fully selected plan may allocate one log to several recipes. Gather the selected
        // total at its first ready acquisition, retaining all crafting dependencies afterwards.
        // Only input-free registered acquisition steps can move; crafting never gets reordered.
        var steps=result.plan().steps();var grouped=new ArrayList<PreparationGraph.Step>();var done=new HashSet<String>();
        for(var step:steps) {
            if(acquisition.contains(step.method())&&step.inputs().isEmpty()) {
                if(!done.add(step.method()))continue;
                int quantity=steps.stream().filter(s->s.method().equals(step.method())).mapToInt(PreparationGraph.Step::quantity).sum();
                int runs=steps.stream().filter(s->s.method().equals(step.method())).mapToInt(PreparationGraph.Step::runs).sum();
                grouped.add(new PreparationGraph.Step(step.method(),runs,step.output(),quantity,step.inputs()));
            } else grouped.add(step);
        }
        var plan=new PreparationGraph.Plan(grouped,result.plan().cost(),result.plan().remaining());
        return new PreparationGraph.Result(result.status(),plan,result.conditions(),result.searched(),result.alternativesTruncated());
    }
}
