package io.github.yufeiyufei888.hearthcrew.gameplay;
import java.util.*;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.*;
public final class RecipeDiscovery {
 public static List<Map<String,Object>> query(CompanionEntity p,List<ResourceLocation> outputs){
  return query(p,outputs,1);
 }
 public static List<Map<String,Object>> query(CompanionEntity p,List<ResourceLocation> outputs,int repetitions){
  if(repetitions<1||repetitions>64)throw new IllegalArgumentException("recipe repetitions 1..64");
  if(outputs.size()>16)throw new IllegalArgumentException("at most 16 recipe queries");var result=new ArrayList<Map<String,Object>>();
  for(var holder:p.level().getRecipeManager().getRecipes()){
   var recipe=holder.value();var output=recipe.getResultItem(p.registryAccess());if(output.isEmpty()||!outputs.contains(BuiltInRegistries.ITEM.getKey(output.getItem())))continue;
   if(!(recipe instanceof CraftingRecipe||recipe instanceof AbstractCookingRecipe))continue;
   String station=recipe instanceof SmokingRecipe?"minecraft:smoker":recipe instanceof BlastingRecipe?"minecraft:blast_furnace":recipe instanceof AbstractCookingRecipe?"minecraft:furnace":((CraftingRecipe)recipe).canCraftInDimensions(2,2)?"inventory_2x2":"minecraft:crafting_table";
   var ingredients=new ArrayList<Map<String,Object>>();for(var ingredient:recipe.getIngredients()){
    if(ingredient.isEmpty())continue;int available=0;for(int i=0;i<36;i++)if(ingredient.test(p.inventory().getItem(i)))available+=p.inventory().getItem(i).getCount();
    ingredients.add(Map.of("alternatives",Arrays.stream(ingredient.getItems()).map(s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).toList(),"availableMatching",available,"requiredForThisCell",1));
   }
   var row=new LinkedHashMap<String,Object>();row.put("recipeId",holder.id().toString());row.put("output",BuiltInRegistries.ITEM.getKey(output.getItem()).toString());row.put("count",output.getCount());row.put("station",station);row.put("ingredients",ingredients);
   row.put("inventoryVersion","inventory:"+io.github.yufeiyufei888.hearthcrew.gameplay.TargetInspection.tools(p).hashCode());
   row.put("observedTick",p.level().getGameTime());
   if(recipe instanceof CraftingRecipe crafting){row.putAll(RecipeActions.assess(p,crafting,repetitions));
    var planner=new ResourcePreparation(p,p.blockPosition(),32,io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Preparation.standard());planner.surveyNearby();planner.tickSurvey();
    var d=planner.craft(holder.id(),p.inventory().countItem(output.getItem())+output.getCount()*repetitions);
    var next=d.step();boolean portable=crafting.canCraftInDimensions(2,2);
    boolean ready=next!=null&&next.kind()==io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.CRAFT&&next.resource().equals(holder.id())&&(portable||next.position()!=null&&RecipeActions.canReachStation(p,next.position()));
    boolean localPlacement=next!=null&&next.kind()==io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.PLACE&&next.position()!=null&&TargetInspection.stances(p,next.position()).stream().anyMatch(pos->pos.distToCenterSqr(p.position())<2)&&PlayerInteractions.placementFace(p,next.position())!=null;
    row.put("stationState",portable?"portable":ready?"reachable":next!=null&&next.position()!=null?"path_unknown":d.searching()?"scan_pending":"not_ready");
    boolean readyDependency=false;
    if(next!=null&&next.kind()==io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.CRAFT){var n=p.level().getRecipeManager().byKey(next.resource()).orElse(null);if(n!=null&&n.value() instanceof CraftingRecipe c)readyDependency=Boolean.TRUE.equals(RecipeActions.assess(p,c,next.count()).get("materialsReady"))&&(c.canCraftInDimensions(2,2)||next.position()!=null&&RecipeActions.canReachStation(p,next.position()));}
    row.put("selfPreparation",Map.of("state",ready||readyDependency||localPlacement?"ready_step":d.searching()?"unknown":"requires_validation","reason",d.reason(),"nextKind",next==null?"none":next.kind().name()));
    row.put("craftableNow",ready&&Boolean.TRUE.equals(row.get("materialsReady")));
   }result.add(row);if(result.size()>=64)break;
  }return result;
 }
}
