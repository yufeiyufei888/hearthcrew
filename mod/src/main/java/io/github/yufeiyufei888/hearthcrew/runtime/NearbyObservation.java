package io.github.yufeiyufei888.hearthcrew.runtime;
import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
/** Incremental loaded-only perception. Water never occupies resource quotas. */
public final class NearbyObservation {
 private static long generation(ServerPlayer p){return io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.generation(p);}
 private static final Map<UUID,Job> jobs=new LinkedHashMap<>();private static final Map<UUID,Job> published=new HashMap<>();private static long sequence;private static int roundRobin;
 private static final Map<Integer,List<BlockPos>> offsets=new HashMap<>();
 public static List<BlockPos> ordered(int radius){return offsets.computeIfAbsent(radius,n->BlockPos.betweenClosedStream(new BlockPos(-n,-n,-n),new BlockPos(n,n,n)).map(BlockPos::immutable).filter(p->p.distSqr(BlockPos.ZERO)<=n*n).sorted(Comparator.comparingDouble(p->p.distSqr(BlockPos.ZERO))).toList());}
 private static String stoneDrop(String id){return switch(id){case "minecraft:stone"->"minecraft:cobblestone";case "minecraft:deepslate"->"minecraft:cobbled_deepslate";default->id;};}
 private static boolean stone(Block block){return Set.of(Blocks.STONE,Blocks.COBBLESTONE,Blocks.DEEPSLATE,Blocks.COBBLED_DEEPSLATE,Blocks.GRANITE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.CALCITE).contains(block);}

 private static final class Job {
  final ServerPlayer body;final BlockPos origin;final int radius;final long generation;int cursor,farCursor,visited;long completed,completedTick;boolean done;
  final Map<String,List<JsonObject>> categories=new LinkedHashMap<>();final Set<Long> empty=new HashSet<>(),water=new HashSet<>();final List<BlockPos> solids=new ArrayList<>(),ground=new ArrayList<>();final List<JsonObject> distant=new ArrayList<>();
  Job(ServerPlayer p,int radius){body=p;origin=p.blockPosition();this.radius=radius;generation=generation(p);}
  void step(){if(done)return;var positions=ordered(radius);int total=positions.size();
   while(cursor<total){if(!ScanBudget.claim(body.server,1))return;var pos=origin.offset(positions.get(cursor++));
    if(pos.distSqr(origin)>radius*radius||!body.level().hasChunkAt(pos)||body.level().isOutsideBuildHeight(pos))continue;
    var state=body.level().getBlockState(pos);visited++;if(state.isAir()){empty.add(pos.asLong());continue;}
    if(state.isFaceSturdy(body.level(),pos,net.minecraft.core.Direction.UP)&&state.getFluidState().isEmpty()&&Math.floorMod(pos.getX(),2)==0&&Math.floorMod(pos.getZ(),2)==0)solids.add(pos);
    if(state.getFluidState().is(net.minecraft.tags.FluidTags.WATER))water.add(pos.asLong());if(state.getBlock() instanceof LiquidBlock)continue;
    String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();String group=state.is(BlockTags.LOGS)?"wood":id.endsWith("_ore")?"ores":stone(state.getBlock())?"stone":state.getBlock() instanceof CropBlock?"food":state.hasBlockEntity()||state.getBlock() instanceof CraftingTableBlock||state.getBlock() instanceof BedBlock?"facilities":null;
    if(group==null)continue;var list=categories.computeIfAbsent(group,k->new ArrayList<>());var value=block(pos,id);value.addProperty("protected",CrewWorldData.get(body.server).playerBlock(body.level(),pos));value.addProperty("visibility","unverified_hidden_allowed");value.addProperty("path","unchecked");value.addProperty("toolRequired",state.requiresCorrectToolForDrops());value.addProperty("protectionReason",CrewWorldData.get(body.server).protectionReason(body.level(),pos));if(group.equals("stone")){value.addProperty("usualDrop",stoneDrop(id));value.addProperty("dropQualification","requires_tool_and_enchantment_check");}if(group.equals("wood"))value.addProperty("gatherValidation","unverified_until_local_scan");list.add(value);
    list.sort(Comparator.comparingDouble(v->distance(v,origin)));if(list.size()>48)list.removeLast();
   }
   while(farCursor<65*65){if(!ScanBudget.claim(body.server,3))return;int at=farCursor++;int x=origin.getX()+(at%65-32)*4,z=origin.getZ()+(at/65-32)*4;
    var column=new BlockPos(x,origin.getY(),z);if(column.distSqr(origin)>128*128||!body.level().hasChunkAt(column))continue;
    int y=body.level().getHeight(Heightmap.Types.WORLD_SURFACE,x,z)-1;var pos=new BlockPos(x,y,z);var state=body.level().getBlockState(pos);
    if(state.is(BlockTags.LOGS)||state.is(BlockTags.LEAVES)){var value=block(pos,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());value.addProperty("kind","surface_tree_hint");value.addProperty("requiresNearInspection",true);int sector=Math.floorMod((int)Math.floor((Math.atan2(z-origin.getZ(),x-origin.getX())+Math.PI)/(Math.PI/4)),8);value.addProperty("sector",sector);if(distant.stream().filter(v->v.get("sector").getAsInt()==sector).count()<8)distant.add(value);}
   }
   for(var floor:solids)if(empty.contains(floor.above().asLong())&&empty.contains(floor.above(2).asLong()))ground.add(floor.above());
   ground.sort(Comparator.comparingDouble(p->p.distSqr(origin)));done=true;completed=++sequence;completedTick=body.level().getGameTime();ExplorationRecord.get(body.server).record(body,origin,visited,distant);
  }
 }
 public static void tick(net.minecraft.server.MinecraftServer server){
  jobs.values().removeIf(j->j.body.server!=server||j.body.isRemoved()||!j.body.isAlive());
  var work=new ArrayList<>(jobs.values());if(work.isEmpty())return;int first=Math.floorMod(roundRobin++,work.size());
  for(int i=0;i<work.size();i++){var job=work.get((first+i)%work.size());job.step();if(job.done)published.put(job.body.getUUID(),job);}
 }
 public static void clear(){jobs.clear();published.clear();}
 public static long revision(ServerPlayer body){var job=published.get(body.getUUID());return job==null||job.body!=body||job.generation!=generation(body)?0:job.completed;}
 public static List<BlockPos> ground(ServerPlayer body,int radius){var j=published.get(body.getUUID());if(j==null||!j.done||j.body!=body||j.generation!=generation(body))return List.of();return j.ground.stream().filter(p->p.distSqr(body.blockPosition())<=radius*radius).limit(64).toList();}
 /** Start bounded perception when a body becomes available, before the first model query. */
 public static void request(ServerPlayer body,int radius){
  if(radius<1||radius>32)throw new IllegalArgumentException("radius 1..32");var job=jobs.get(body.getUUID());
  if(job==null||job.body!=body||job.generation!=generation(body)||job.radius<radius||job.origin.distSqr(body.blockPosition())>64||job.done&&body.level().getGameTime()-job.completedTick>600){job=new Job(body,job!=null&&job.body==body?Math.max(radius,job.radius):radius);jobs.put(body.getUUID(),job);}
 }
 public static JsonObject scan(ServerPlayer body,int radius){
  request(body,radius);var job=jobs.get(body.getUUID());
  var inProgress=job;var previous=published.get(body.getUUID());
  boolean refreshing=!job.done&&previous!=null&&previous.body==body&&previous.generation==generation(body)&&previous.radius>=radius&&previous.origin.distSqr(body.blockPosition())<=64;
  if(refreshing)job=previous;
  var result=new JsonObject();result.addProperty("state",refreshing?"refreshing":job.done?"ready":"scanning");result.addProperty("refreshVisitedBlocks",inProgress.visited);result.addProperty("requestedRadius",radius);result.addProperty("snapshotAgeTicks",job.done?body.level().getGameTime()-job.completedTick:0);result.addProperty("candidatePolicy","cached clues require fresh target inspection; refresh does not erase prior results");result.addProperty("revision",job.completed);result.addProperty("radius",job.radius);result.addProperty("farRadius",128);result.addProperty("resourceScanIncludesHidden",true);result.addProperty("terrainInference","unknown_from_resource_scan");result.addProperty("unloaded","unknown");result.addProperty("visitedBlocks",job.visited);result.addProperty("scanOrigin",job.origin.toShortString());result.addProperty("completedGameTick",job.done?job.completedTick:-1);result.addProperty("absenceIsConfirmed",job.done&&!refreshing);result.addProperty("noResultMeaning",job.done?"no candidate in inspected loaded area; not world-wide absence":"scan_incomplete_unknown");result.addProperty("perTeamTickCheckLimit",4096);result.addProperty("perTeamTickMilliseconds",2);result.addProperty("surfaceSampleStep",4);
  var blocks=new JsonArray();job.categories.values().forEach(list->list.forEach(v->blocks.add(v.deepCopy())));result.add("blocks",blocks);result.add("categories",new Gson().toJsonTree(job.categories));var totals=new JsonObject();job.categories.forEach((k,v)->totals.addProperty(k,v.size()));result.add("candidateCounts",totals);result.add("farHints",new Gson().toJsonTree(job.distant));
  var entities=new JsonArray();body.level().getEntities(body,body.getBoundingBox().inflate(128),e->e.isAlive()&&e.distanceToSqr(body)<=128*128&&(e.distanceToSqr(body)<=32*32||body.level().canSeeSky(e.blockPosition()))).stream().sorted(Comparator.comparingDouble(body::distanceToSqr)).limit(96).forEach(e->{
   var value=new JsonObject();value.addProperty("entityId",e.getUUID().toString());value.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());value.addProperty("name",e.getName().getString());value.add("position",position(e.getX(),e.getY(),e.getZ()));value.addProperty("path","unverified");if(e instanceof LivingEntity l)value.addProperty("health",l.getHealth());if(e instanceof ItemEntity d){value.addProperty("item",BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).toString());value.addProperty("count",d.getItem().getCount());}entities.add(value);
  });result.add("entities",entities);
  var terrain=new JsonObject();terrain.addProperty("inWater",body.isInWater());terrain.addProperty("eyesUnderWater",body.isUnderWater());terrain.addProperty("air",body.getAirSupply());terrain.add("standable",new Gson().toJsonTree(ground(body,radius).stream().map(io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain::position).toList()));terrain.addProperty("candidateReachability","unverified");var scanJob=job;terrain.add("shoreCandidates",new Gson().toJsonTree(ground(body,radius).stream().filter(p->java.util.Arrays.stream(net.minecraft.core.Direction.Plane.HORIZONTAL.stream().toArray(net.minecraft.core.Direction[]::new)).anyMatch(d->scanJob.water.contains(p.relative(d).asLong())||scanJob.water.contains(p.relative(d).below().asLong()))).limit(16).map(io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain::position).toList()));
  var air=new JsonArray();for(int up=0;up<=32;up++){var at=body.blockPosition().above(up);if(job.empty.contains(at.asLong())&&job.empty.contains(at.above().asLong())){air.add(position(at.getX(),at.getY(),at.getZ()));if(air.size()>=8)break;}}terrain.add("breathableAbove",air);result.add("explorationHistory",ExplorationRecord.get(body.server).observe(body));result.add("terrain",terrain);
  return result;
 }
 private static double distance(JsonObject obj,BlockPos p){var v=obj.getAsJsonObject("position");return p.distSqr(new BlockPos(v.get("x").getAsInt(),v.get("y").getAsInt(),v.get("z").getAsInt()));}
 private static JsonObject block(BlockPos p,String id){var result=new JsonObject();result.addProperty("block",id);result.add("position",position(p.getX(),p.getY(),p.getZ()));return result;}
 private static JsonObject position(double x,double y,double z){var result=new JsonObject();result.addProperty("x",x);result.addProperty("y",y);result.addProperty("z",z);return result;}
}
