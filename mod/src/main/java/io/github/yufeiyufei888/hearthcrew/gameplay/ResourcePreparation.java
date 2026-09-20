package io.github.yufeiyufei888.hearthcrew.gameplay;

import java.util.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only bounded dependency planning. Only BodyExecutor executes the returned step. */
public final class ResourcePreparation {
 public record Decision(BodyOrder step,String reason,boolean searching){}
 private final CompanionEntity body;
 private final BlockPos origin;
 private final int radius;
 private final BodyOrder.Preparation policy;
 private final Map<Item,List<RecipeHolder<SmeltingRecipe>>> cooking=new HashMap<>();
 private final Iterator<BlockPos> scan;
 private final Map<Block,List<BlockPos>> observed=new HashMap<>();
 private final Map<Item,List<RecipeHolder<CraftingRecipe>>> recipes=new HashMap<>();
 private boolean scanned; private final Set<BlockPos> rejected=new HashSet<>();
 public void reject(BlockPos pos){if(pos!=null)rejected.add(pos.immutable());}
 public ResourcePreparation(CompanionEntity body,BlockPos origin,int radius,BodyOrder.Preparation policy){
  this.body=body;this.origin=origin;this.radius=radius;this.policy=policy;
  for(var h:body.level().getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)){var out=h.value().getResultItem(body.registryAccess());if(!out.isEmpty())cooking.computeIfAbsent(out.getItem(),k->new ArrayList<>()).add(h);}
  // Shell order finds the nearby material first; no corner-first full-cube delay.
  scan=BlockPos.withinManhattan(origin,radius,radius,radius).iterator();
  for(var holder:body.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)){
   var out=holder.value().getResultItem(body.registryAccess());if(!out.isEmpty()&&!holder.value().isSpecial())recipes.computeIfAbsent(out.getItem(),k->new ArrayList<>()).add(holder);
  }
 }
 public void tickSurvey(){long end=System.nanoTime()+1_000_000;for(int n=0;n<1024&&scan.hasNext()&&System.nanoTime()<end;n++){
  if(!io.github.yufeiyufei888.hearthcrew.runtime.ScanBudget.claim(body.server,1))return;var p=scan.next().immutable();if(p.distSqr(origin)>radius*radius||!body.level().hasChunkAt(p))continue;var b=body.level().getBlockState(p).getBlock();if(b!=Blocks.AIR)observed.computeIfAbsent(b,k->new ArrayList<>()).add(p);
 }scanned=!scan.hasNext();}
 public boolean scanned(){return scanned;}
 public List<BlockPos> sources(Item item){
  var result=new ArrayList<BlockPos>();for(var block:sourceBlocks(item))for(var p:observed.getOrDefault(block,List.of()))if(!rejected.contains(p)&&body.level().getBlockState(p).is(block)&&!CrewWorldData.get(body.server).playerBlock(body.level(),p))result.add(p);
  result.sort(Comparator.comparingDouble(p->p.distSqr(body.blockPosition())));return result;
 }
 public static List<Block> sourceBlocks(Item item){
  if(item==Items.COBBLESTONE)return List.of(Blocks.STONE,Blocks.COBBLESTONE);
  if(item==Items.COBBLED_DEEPSLATE)return List.of(Blocks.DEEPSLATE,Blocks.COBBLED_DEEPSLATE);
  if(item==Items.COAL)return List.of(Blocks.COAL_ORE,Blocks.DEEPSLATE_COAL_ORE);
  if(item==Items.RAW_IRON)return List.of(Blocks.IRON_ORE,Blocks.DEEPSLATE_IRON_ORE);
  if(item==Items.RAW_COPPER)return List.of(Blocks.COPPER_ORE,Blocks.DEEPSLATE_COPPER_ORE);
  if(item==Items.RAW_GOLD)return List.of(Blocks.GOLD_ORE,Blocks.DEEPSLATE_GOLD_ORE);
  if(item==Items.DIAMOND)return List.of(Blocks.DIAMOND_ORE,Blocks.DEEPSLATE_DIAMOND_ORE);
  if(item==Items.REDSTONE)return List.of(Blocks.REDSTONE_ORE,Blocks.DEEPSLATE_REDSTONE_ORE);
  if(item==Items.LAPIS_LAZULI)return List.of(Blocks.LAPIS_ORE,Blocks.DEEPSLATE_LAPIS_ORE);
  if(item instanceof BlockItem b && (naturalSource(b.getBlock().defaultBlockState())||b.getBlock().defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)))return List.of(b.getBlock());
  if(item==Items.WHEAT)return List.of(Blocks.WHEAT);
  if(item==Items.CARROT)return List.of(Blocks.CARROTS);
  if(item==Items.POTATO)return List.of(Blocks.POTATOES);
  if(item==Items.BEETROOT)return List.of(Blocks.BEETROOTS);
  return List.of();
 }
 public Decision tool(BlockState target){return tool(target,16);}
 public Decision tool(BlockState target,int durability){
  if(readyTool(target,durability)>=0)return new Decision(null,"tool already present",false);
  Decision missing=null;
  for(var id:TargetInspection.suitablePickaxes(target)){
   var wanted=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
   var d=item(wanted,body.inventory().countItem(wanted)+1,0,new HashSet<>());
   if(d.step()!=null||d.reason().contains("STATION_NO_LEGAL_SITE"))return d;if(missing==null)missing=d;
  }
  return missing==null?new Decision(null,"NO_SUPPORTED_TOOL_RECIPE",!scanned):missing;
 }
 public int readyTool(BlockState target,int durability){
  int best=-1;float speed=-1;for(int i=0;i<36;i++){var stack=body.inventory().getItem(i);
   if(stack.isEmpty()||target.requiresCorrectToolForDrops()&&!stack.isCorrectToolForDrops(target)||stack.isDamageableItem()&&stack.getMaxDamage()-stack.getDamageValue()<durability)continue;
   if(stack.getDestroySpeed(target)>speed){best=i;speed=stack.getDestroySpeed(target);}}
  return best;
 }
 public Decision craft(ResourceLocation recipeId,int desired){
  var holder=body.level().getRecipeManager().byKey(recipeId).orElse(null);
  if(holder==null||!(holder.value() instanceof CraftingRecipe recipe))return new Decision(null,"UNKNOWN_CRAFTING_RECIPE",false);
  var wanted=recipe.getResultItem(body.registryAccess()).getItem();var old=recipes.get(wanted);
  @SuppressWarnings("unchecked") var exact=(RecipeHolder<CraftingRecipe>)(RecipeHolder<?>)holder;
  recipes.put(wanted,List.of(exact));try{return item(wanted,desired);}finally{if(old==null)recipes.remove(wanted);else recipes.put(wanted,old);}
 }
 public boolean hasRecipe(Item item){return recipes.containsKey(item)||cooking.containsKey(item);}
 public Decision item(Item wanted,int amount){return item(wanted,amount,0,new HashSet<>());}
 private Decision item(Item wanted,int amount,int depth,Set<Item> parents){
  if(body.inventory().countItem(wanted)>=amount)return new Decision(null,"inventory satisfied",false);
  if(depth>=policy.maxDepth()||!parents.add(wanted))return new Decision(null,"PREPARATION_DEPTH_OR_RECIPE_CYCLE: "+id(wanted),false);
  try{
   int missing=amount-body.inventory().countItem(wanted);
   // Actual observed natural sources are preferable to speculative recipe branches.
   for(var pos:sources(wanted).stream().limit(16).toList()){
    var state=body.level().getBlockState(pos);
    if(state.is(net.minecraft.tags.BlockTags.LOGS)&&BodyOrder.supportedGatherResource(BuiltInRegistries.BLOCK.getKey(state.getBlock())))
     return new Decision(BodyOrder.gather(BuiltInRegistries.BLOCK.getKey(state.getBlock()),Math.min(64,missing)),"gather preparation material",false);
    if(!naturalSource(state)||!safeSource(pos))continue;
    if(TargetInspection.bestTool(body,state)<0){
     for(var tool:TargetInspection.suitablePickaxes(state)){var d=item(BuiltInRegistries.ITEM.get(ResourceLocation.parse(tool)),1,depth+1,parents);if(d.step()!=null)return d;}
     continue;
    }
    return new Decision(BodyOrder.mine(pos),"collect preparation material",false);
   }
   var hunt=hunt(wanted);if(hunt!=null)return new Decision(hunt,"hunt observed unowned food source",false);
   for(var h:cooking.getOrDefault(wanted,List.of())){
    var recipe=h.value();var inputs=Arrays.stream(recipe.getIngredients().getFirst().getItems()).map(ItemStack::getItem).sorted(Comparator.comparingInt(i->itemCost(i,0))).toList();
    if(inputs.isEmpty())continue;var input=inputs.getFirst();int batch=Math.min(64,missing);
    if(body.inventory().countItem(input)<batch){var d=item(input,batch,depth+1,parents);if(d.step()!=null)return d;continue;}
    var station=observed.getOrDefault(Blocks.FURNACE,List.of()).stream().filter(p->body.level().getBlockState(p).is(Blocks.FURNACE)&&PlayerInteractions.shared(body,p)&&body.level().getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f&&!f.getPersistentData().contains("HearthCrewOrder")&&f.isEmpty()).findFirst().orElse(null);
    if(station==null){
     if(body.inventory().countItem(Items.FURNACE)==0){var d=item(Items.FURNACE,1,depth+1,parents);if(d.step()!=null)return d;continue;}
     var site=placementSite();if(site==null)return new Decision(null,"PREPARATION_FURNACE_NO_LEGAL_SITE",false);
     return new Decision(new BodyOrder(BodyOrder.Kind.PLACE,site,null,0,id(Items.FURNACE)),"place required furnace",false);
    }
    int ticks=recipe.getCookingTime()*batch;boolean fuelReady=false;
    for(int slot=0;slot<36;slot++){var stack=body.inventory().getItem(slot);if(stack.getItem()!=input&&stack.getBurnTime(RecipeType.SMELTING)>0&&stack.getBurnTime(RecipeType.SMELTING)*stack.getCount()>=ticks){fuelReady=true;break;}}
    if(!fuelReady){
     var fuels=new ArrayList<Item>();for(var fuel:BuiltInRegistries.ITEM)if(fuel!=input&&new ItemStack(fuel).getBurnTime(RecipeType.SMELTING)>0)fuels.add(fuel);
     fuels.sort(Comparator.comparingInt(i->itemCost(i,0)));boolean planned=false;
     for(var fuel:fuels.stream().limit(16).toList()){int n=(ticks+new ItemStack(fuel).getBurnTime(RecipeType.SMELTING)-1)/new ItemStack(fuel).getBurnTime(RecipeType.SMELTING);if(n>64)continue;var d=item(fuel,n,depth+1,parents);if(d.step()!=null)return d;}
     return new Decision(null,"PREPARATION_FUEL_UNAVAILABLE",!scanned);
    }
    return new Decision(new BodyOrder(BodyOrder.Kind.PROCESS,station,null,batch,id(input)),"deposit real cooking input and fuel; wait for vanilla processing",false);
   }
   var choices=new ArrayList<>(recipes.getOrDefault(wanted,List.of()));choices.sort(Comparator.comparingInt(h->recipeCost(h.value())));
   for(var holder:choices){
    var recipe=holder.value();var required=new LinkedHashMap<Item,Integer>();boolean viable=true;
    int repeats=(missing+recipe.getResultItem(body.registryAccess()).getCount()-1)/recipe.getResultItem(body.registryAccess()).getCount();
    for(var ingredient:recipe.getIngredients()){
     if(ingredient.isEmpty())continue;
     var alternatives=Arrays.stream(ingredient.getItems()).map(ItemStack::getItem).distinct().sorted(Comparator.comparingInt(i->itemCost(i,required.getOrDefault(i,0)))).toList();
     Item selected=alternatives.stream().filter(i->!parents.contains(i)).findFirst().orElse(null);if(selected==null){viable=false;break;}required.merge(selected,repeats,Integer::sum);
    }
    if(!viable)continue;
    for(var entry:required.entrySet())if(body.inventory().countItem(entry.getKey())<entry.getValue()){
     var d=item(entry.getKey(),entry.getValue(),depth+1,parents);if(d.step()!=null)return d;viable=false;break;
    }
    if(!viable)continue;
    BlockPos station=null;
    if(!recipe.canCraftInDimensions(2,2)){
     station=observed.getOrDefault(Blocks.CRAFTING_TABLE,List.of()).stream().filter(p->body.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)&&PlayerInteractions.shared(body,p)).min(Comparator.comparingDouble(p->p.distSqr(body.blockPosition()))).orElse(null);
     if(station==null){
      if(body.inventory().countItem(Items.CRAFTING_TABLE)==0){var d=item(Items.CRAFTING_TABLE,1,depth+1,parents);if(d.step()!=null)return d;continue;}
      for(var p:BlockPos.betweenClosed(body.blockPosition().offset(-3,-1,-3),body.blockPosition().offset(3,1,3))){
       if(p.distSqr(origin)>radius*radius||!body.level().getBlockState(p).isAir()||CrewWorldData.get(body.server).playerBlock(body.level(),p)||!TravelTerrain.blockers(body,net.minecraft.world.phys.Vec3.atBottomCenterOf(p)).isEmpty()||PlayerInteractions.placementFace(body,p)==null)continue;
       return new Decision(new BodyOrder(BodyOrder.Kind.PLACE,p,null,0,id(Items.CRAFTING_TABLE)),"place required workbench",false);
      }
      return new Decision(null,"PREPARATION_STATION_NO_LEGAL_SITE",false);
     }
    }
    return new Decision(new BodyOrder(BodyOrder.Kind.CRAFT,station,null,repeats,holder.id()),"craft dependency "+id(wanted),false);
   }
   return new Decision(null,"PREPARATION_MATERIAL_OR_CAPABILITY: "+id(wanted)+" missing="+missing,!scanned);
  }finally{parents.remove(wanted);}
 }
 private int itemCost(Item i,int reserved){return body.inventory().countItem(i)>reserved?0:availabilityCost(i,new HashSet<>(),0);}
 private int availabilityCost(Item item,Set<Item> seen,int depth){
  if(body.inventory().countItem(item)>0)return 0;
  if(!sources(item).isEmpty())return 1;
  if(depth>=policy.maxDepth()||!seen.add(item))return 100;
  try{return recipes.getOrDefault(item,List.of()).stream().mapToInt(h->1+h.value().getIngredients().stream().filter(i->!i.isEmpty()).mapToInt(i->Arrays.stream(i.getItems()).mapToInt(s->availabilityCost(s.getItem(),seen,depth+1)).min().orElse(100)).sum()).min().orElse(100);}finally{seen.remove(item);}
 }
 private int recipeCost(CraftingRecipe r){return r.getIngredients().stream().filter(i->!i.isEmpty()).mapToInt(i->Arrays.stream(i.getItems()).mapToInt(s->itemCost(s.getItem(),0)).min().orElse(20)).sum();}
 private static ResourceLocation id(Item i){return BuiltInRegistries.ITEM.getKey(i);}
 private BlockPos placementSite(){
  for(var p:BlockPos.betweenClosed(body.blockPosition().offset(-3,-1,-3),body.blockPosition().offset(3,1,3)))if(p.distSqr(origin)<=radius*radius&&body.level().getBlockState(p).isAir()&&!CrewWorldData.get(body.server).playerBlock(body.level(),p)&&TravelTerrain.blockers(body,net.minecraft.world.phys.Vec3.atBottomCenterOf(p)).isEmpty()&&PlayerInteractions.placementFace(body,p)!=null)return p.immutable();return null;
 }
 private BodyOrder hunt(Item wanted){
  Class<? extends net.minecraft.world.entity.animal.Animal> type=wanted==Items.BEEF?net.minecraft.world.entity.animal.Cow.class:wanted==Items.PORKCHOP?net.minecraft.world.entity.animal.Pig.class:wanted==Items.MUTTON?net.minecraft.world.entity.animal.Sheep.class:wanted==Items.CHICKEN?net.minecraft.world.entity.animal.Chicken.class:null;
  if(type==null)return null;
  return body.level().getEntitiesOfClass(type,new net.minecraft.world.phys.AABB(origin).inflate(radius),a->a.isAlive()&&!a.isBaby()&&!a.isLeashed()&&a.getCustomName()==null&&!a.isPassenger()&&a.blockPosition().distSqr(origin)<=radius*radius).stream()
   .filter(a->!CrewWorldData.get(body.server).playerBlock(body.level(),a.blockPosition())&&!CrewWorldData.get(body.server).playerBlock(body.level(),a.blockPosition().below()))
   .filter(a->java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(a.blockPosition().offset(-3,-1,-3),a.blockPosition().offset(3,1,3)).spliterator(),false).noneMatch(p->body.level().getBlockState(p).getBlock() instanceof FenceBlock||body.level().getBlockState(p).getBlock() instanceof FenceGateBlock))
   .min(Comparator.comparingDouble(body::distanceToSqr)).map(a->new BodyOrder(BodyOrder.Kind.ATTACK,null,a.getUUID(),0)).orElse(null);
 }
 private boolean safeSource(BlockPos pos){
  if(pos.getY()<body.getY()&&body.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos).expandTowards(0,2,0)))return false;
  for(var d:Direction.values()){var n=pos.relative(d);if(!body.level().hasChunkAt(n)||!body.level().getFluidState(n).isEmpty()||body.level().getBlockState(n).getBlock() instanceof FallingBlock)return false;}
  return true;
 }
 public static boolean naturalSource(BlockState state){
  if(state.hasBlockEntity())return false;
  if(state.getBlock() instanceof CropBlock crop)return crop.isMaxAge(state);
  return state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)||List.of(Blocks.STONE,Blocks.DEEPSLATE,Blocks.GRANITE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.CALCITE,Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.SAND,Blocks.RED_SAND,Blocks.GRAVEL,Blocks.CLAY).contains(state.getBlock());
 }
 public BlockPos knownWorkbench(){return observed.getOrDefault(Blocks.CRAFTING_TABLE,List.of()).stream().filter(p->body.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)&&PlayerInteractions.shared(body,p)).min(Comparator.comparingDouble(p->p.distSqr(body.blockPosition()))).orElse(null);}
 public void surveyNearby(){changed();}
 public void changed(){
  // Newly placed stations must be discoverable before the survey revisits their section.
  for(var p:BlockPos.betweenClosed(body.blockPosition().offset(-4,-2,-4),body.blockPosition().offset(4,2,4)))if(p.distSqr(origin)<=radius*radius&&body.level().hasChunkAt(p)){
   var block=body.level().getBlockState(p).getBlock();if(block==Blocks.CRAFTING_TABLE||block==Blocks.FURNACE){var list=observed.computeIfAbsent(block,k->new ArrayList<>());if(!list.contains(p))list.add(p.immutable());}
  }
 }
}
