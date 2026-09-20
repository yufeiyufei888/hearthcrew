package io.github.yufeiyufei888.hearthcrew.entity;

import java.util.*;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain;

/** Evidence only: never changes inventory, replays a block break, or promotes an old action. */
public final class HarvestLedger {
    private static final class Entry {
        String actionId,dimension; final Set<BlockPos> broken=new LinkedHashSet<>();
        final Map<UUID,Integer> owed=new LinkedHashMap<>();final Map<String,Integer> acquired=new TreeMap<>();
        final Map<String,Integer> otherAcquired=new TreeMap<>();
        final Map<UUID,Map<String,Object>> closed=new LinkedHashMap<>();
        final Map<String,Map<String,Integer>> collectors=new LinkedHashMap<>();
        final Map<UUID,Map<String,Object>> facts=new LinkedHashMap<>();
    }
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    public void broken(String action,String dimension,BlockPos pos,Map<UUID,Integer> drops){
        var e=entries.computeIfAbsent(action,k->{var v=new Entry();v.actionId=k;v.dimension=dimension;return v;});
        if(e.broken.add(pos.immutable()))drops.forEach((k,v)->e.owed.merge(k,v,Integer::sum));
    }
    /** Native animal drops are recorded without inventing a broken block. */
    public void animalDrops(String action,String dimension,Map<UUID,Integer> drops){
        var e=entries.computeIfAbsent(action,k->{var v=new Entry();v.actionId=k;v.dimension=dimension;return v;});
        drops.forEach((id,n)->e.owed.putIfAbsent(id,n));
    }
    public int acquired(UUID id,int amount,String resource,boolean byOwner){return acquired(id,amount,resource,byOwner,null);}
    public int acquired(UUID id,int amount,String resource,boolean byOwner,UUID collector){
        int left=amount;
        for(var e:entries.values()) {int n=Math.min(left,e.owed.getOrDefault(id,0));if(n<=0)continue;
            e.owed.computeIfPresent(id,(k,v)->v==n?null:v-n);(byOwner?e.acquired:e.otherAcquired).merge(resource,n,Integer::sum);if(collector!=null)e.collectors.computeIfAbsent(collector.toString(),k->new TreeMap<>()).merge(resource,n,Integer::sum);left-=n;if(left==0)break;}
        return amount-left;
    }
    public boolean claims(UUID id){return entries.values().stream().anyMatch(e->e.owed.containsKey(id));}
    public void removed(UUID id,String reason){for(var e:entries.values())if(e.owed.containsKey(id)){
        var facts=new LinkedHashMap<>(e.facts.getOrDefault(id,Map.of()));facts.put("availability","removed");facts.put("removalEvidence",reason);facts.put("entityId",id.toString());facts.put("count",e.owed.remove(id));e.closed.put(id,facts);e.facts.remove(id);
    }}
    /** A missing UUID is not proof of loss: unloaded chunks and old saves stay unknown. */
    public List<Map<String,Object>> observe(CompanionEntity body){
        int checked=0;for(var e:entries.values())for(var id:e.owed.keySet()){
            if(++checked>64)break;var facts=new LinkedHashMap<>(e.facts.getOrDefault(id,Map.of()));
            var entity=e.dimension.equals(body.level().dimension().location().toString())?body.serverLevel().getEntity(id):null;
            if(entity instanceof net.minecraft.world.entity.item.ItemEntity item&&item.isAlive()&&body.distanceToSqr(item)<=1024){
                facts.put("availability",item.hasPickUpDelay()?"pickup_delayed":"visible");facts.put("lastSeenGameTick",body.serverLevel().getGameTime());
                facts.put("position",Map.of("x",item.getX(),"y",item.getY(),"z",item.getZ()));facts.put("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());facts.put("entityCount",item.getItem().getCount());
            }else if(!"removed".equals(facts.get("availability")))facts.put("availability","not_observable");
            e.facts.put(id,facts);
        }return reports();
    }
    public int merged(UUID source,UUID destination,int amount){int left=amount;for(var e:entries.values()){
        int n=Math.min(left,e.owed.getOrDefault(source,0));if(n<=0)continue;e.owed.computeIfPresent(source,(k,v)->v==n?null:v-n);e.owed.merge(destination,n,Integer::sum);e.facts.remove(destination);if(!e.owed.containsKey(source))e.facts.remove(source);left-=n;if(left==0)break;
    }return amount-left;}
    public Map<String,Object> report(String action){var e=entries.get(action);if(e==null)return Map.of();return Map.of(
        "actionId",e.actionId,"dimension",e.dimension,"broken",e.broken.size(),"brokenPositions",e.broken.stream().map(TravelTerrain::position).toList(),
        "pendingDrops",e.owed.entrySet().stream().map(v->{var row=new LinkedHashMap<String,Object>(e.facts.getOrDefault(v.getKey(),Map.of("availability","not_observable")));row.put("entityId",v.getKey().toString());row.put("count",v.getValue());return row;}).toList(),
        "pickupAttribution",Map.copyOf(e.collectors),"closedDrops",List.copyOf(e.closed.values()),"acquired",Map.copyOf(e.acquired),"acquiredByOthers",Map.copyOf(e.otherAcquired),"recovery",e.owed.isEmpty()?(e.closed.isEmpty()?"accounted":"unavailable"):e.owed.keySet().stream().allMatch(id->"removed".equals(e.facts.getOrDefault(id,Map.of()).get("availability")))?"unavailable":"pickup_or_inspect_required");}
    public List<Map<String,Object>> reports(){return entries.keySet().stream().map(this::report).toList();}
    public boolean pendingAt(String dimension,BlockPos p){return entries.values().stream().anyMatch(e->e.dimension.equals(dimension)&&e.broken.contains(p)&&!e.owed.isEmpty());}
    public void save(CompoundTag tag){tag.putString("harvestEvidence",new Gson().toJson(reports()));}
    public void restore(CompoundTag tag){if(!tag.contains("harvestEvidence"))return;
        for(var raw:JsonParser.parseString(tag.getString("harvestEvidence")).getAsJsonArray()){
            var v=raw.getAsJsonObject();var e=new Entry();e.actionId=v.get("actionId").getAsString();e.dimension=v.get("dimension").getAsString();
            for(var p:v.getAsJsonArray("brokenPositions")){var xyz=p.getAsJsonObject();e.broken.add(new BlockPos(xyz.get("x").getAsInt(),xyz.get("y").getAsInt(),xyz.get("z").getAsInt()));}
            for(var p:v.getAsJsonArray("pendingDrops")){var d=p.getAsJsonObject();var id=UUID.fromString(d.get("entityId").getAsString());e.owed.put(id,d.get("count").getAsInt());var facts=new LinkedHashMap<String,Object>();for(String key:List.of("availability","removalEvidence","item"))if(d.has(key))facts.put(key,d.get(key).getAsString());if(d.has("lastSeenGameTick"))facts.put("lastSeenGameTick",d.get("lastSeenGameTick").getAsLong());if(d.has("position")){var xyz=d.getAsJsonObject("position");facts.put("position",Map.of("x",xyz.get("x").getAsDouble(),"y",xyz.get("y").getAsDouble(),"z",xyz.get("z").getAsDouble()));}e.facts.put(id,facts);}
            if(v.has("pickupAttribution"))for(var actor:v.getAsJsonObject("pickupAttribution").entrySet()){var counts=new TreeMap<String,Integer>();for(var item:actor.getValue().getAsJsonObject().entrySet())counts.put(item.getKey(),item.getValue().getAsInt());e.collectors.put(actor.getKey(),counts);}
            if(v.has("closedDrops"))for(var d:v.getAsJsonArray("closedDrops")){var row=d.getAsJsonObject();Map<String,Object> values=new Gson().fromJson(row,new com.google.gson.reflect.TypeToken<Map<String,Object>>(){}.getType());e.closed.put(UUID.fromString(row.get("entityId").getAsString()),values);}
            for(var id:List.copyOf(e.owed.keySet()))if("removed".equals(e.facts.getOrDefault(id,Map.of()).get("availability"))){var facts=new LinkedHashMap<>(e.facts.remove(id));facts.put("entityId",id.toString());facts.put("count",e.owed.remove(id));e.closed.put(id,facts);}
            for(var a:v.getAsJsonObject("acquired").entrySet())e.acquired.put(a.getKey(),a.getValue().getAsInt());
            if(v.has("acquiredByOthers"))for(var a:v.getAsJsonObject("acquiredByOthers").entrySet())e.otherAcquired.put(a.getKey(),a.getValue().getAsInt());entries.put(e.actionId,e);
        }
    }
}
