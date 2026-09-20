package io.github.yufeiyufei888.hearthcrew.runtime;
import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
/** Bounded world-local evidence, never a source of invented terrain or inventory. */
public final class ExplorationRecord extends SavedData {
 private final LinkedHashMap<String,CompoundTag> regions=new LinkedHashMap<>();
 public ExplorationRecord(){}
 private ExplorationRecord(CompoundTag tag){for(var value:tag.getList("regions",Tag.TAG_COMPOUND)){var r=(CompoundTag)value;regions.put(r.getString("key"),r.copy());}trim();}
 public static ExplorationRecord get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(new Factory<>(ExplorationRecord::new,(tag,provider)->new ExplorationRecord(tag)),"hearthcrew_exploration");}
 private void trim(){while(regions.size()>256)regions.remove(regions.keySet().iterator().next());}
 public void record(ServerPlayer body,BlockPos center,int inspected,List<JsonObject> hints){
  String key=body.level().dimension().location()+":"+Math.floorDiv(center.getX(),16)+":"+Math.floorDiv(center.getZ(),16);
  var row=new CompoundTag();row.putString("key",key);row.putString("dimension",body.level().dimension().location().toString());row.putUUID("observer",body.getUUID());row.putInt("x",center.getX());row.putInt("y",center.getY());row.putInt("z",center.getZ());row.putLong("gameTick",body.level().getGameTime());row.putInt("inspected",inspected);row.putInt("treeHints",hints.size());regions.remove(key);regions.put(key,row);trim();setDirty();
 }
 public static String key(ServerPlayer body,BlockPos center){return body.level().dimension().location()+":"+Math.floorDiv(center.getX(),16)+":"+Math.floorDiv(center.getZ(),16);}
 public Set<String> observedRegions(){return Set.copyOf(regions.keySet());}
 public boolean observedAt(ServerPlayer body,BlockPos target,long since){var row=regions.get(key(body,target));return row!=null&&row.getLong("gameTick")>=since;}
 public JsonArray observe(ServerPlayer body){var result=new JsonArray();var rows=new ArrayList<>(regions.values());Collections.reverse(rows);for(var r:rows){if(!r.getString("dimension").equals(body.level().dimension().location().toString()))continue;var v=new JsonObject();v.addProperty("region",r.getString("key"));v.addProperty("observedAtGameTick",r.getLong("gameTick"));v.addProperty("treeHints",r.getInt("treeHints"));v.addProperty("currentReachability","unknown_recheck");result.add(v);if(result.size()>=32)break;}return result;}
 @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider){var rows=new ListTag();regions.values().forEach(r->rows.add(r.copy()));tag.put("regions",rows);return tag;}
}
