package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World-local experimental identity/results journal. Loading never replays an upstream tool. */
final class BackendJournal extends SavedData {
    record Entry(String actionId,String fingerprint,long generation,String state,String result,int accessSpent,int primaryBroken) {}
    private UUID worldId;
    private ExecutionLog log;
    private long walSequence;
    private record Durable(long sequence,UUID world,UUID body,long generation,Entry entry,Set<String> facilities,Set<String> structures){}
    private final Map<UUID,Long> generations=new LinkedHashMap<>();
    private final Map<UUID,LinkedHashMap<String,Entry>> actions=new LinkedHashMap<>();
    private final Set<String> publicFacilities=new LinkedHashSet<>();
    private final Set<String> placedStructures=new LinkedHashSet<>();
    BackendJournal(){worldId=UUID.randomUUID();setDirty();}
    private BackendJournal(CompoundTag input) {
        if(input.getInt("schema")!=1||!input.hasUUID("worldId"))throw new IllegalArgumentException("Invalid backend journal; preserve file");
        worldId=input.getUUID("worldId");walSequence=input.getLong("walSequence");
        for(var entry:input.getList("publicFacilities",Tag.TAG_STRING))publicFacilities.add(entry.getAsString());
        for(var entry:input.getList("placedStructures",Tag.TAG_STRING))placedStructures.add(entry.getAsString());
        for(var raw:input.getList("bodies",Tag.TAG_COMPOUND)) {
            var tag=(CompoundTag)raw;var id=tag.getUUID("id");
            if(generations.putIfAbsent(id,tag.getLong("generation"))!=null)throw new IllegalArgumentException("Duplicate body identity");
            var rows=new LinkedHashMap<String,Entry>();actions.put(id,rows);
            for(var row:tag.getList("actions",Tag.TAG_COMPOUND)) {
                var a=(CompoundTag)row;String state=a.getString("state");
                if(!terminal(state))state="RECONCILE_REQUIRED";
                var entry=new Entry(a.getString("actionId"),a.getString("fingerprint"),a.getLong("generation"),state,a.getString("result"),a.getInt("accessSpent"),a.getInt("primaryBroken"));
                if(entry.actionId().isBlank()||entry.fingerprint().isBlank()||rows.putIfAbsent(entry.actionId(),entry)!=null)throw new IllegalArgumentException("Invalid action identity");
            }
        }
        setDirty();
    }
    static BackendJournal get(MinecraftServer server) {
        var journal=server.overworld().getDataStorage().computeIfAbsent(new Factory<>(BackendJournal::new,(tag,p)->new BackendJournal(tag)),"hearthcrew_backend_lab");
        if(journal.log==null)journal.attach(server);
        return journal;
    }
    private void attach(MinecraftServer server){
        var path=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/hearthcrew-execution-v1.jsonl");
        if(java.nio.file.Files.exists(path))try(var lines=java.nio.file.Files.newBufferedReader(path,java.nio.charset.StandardCharsets.UTF_8)){
            String line;UUID loggedWorld=null;
            while((line=lines.readLine())!=null){
                // A torn final record is not permission to replay: fail closed and retain the file.
                var row=new com.google.gson.Gson().fromJson(line,Durable.class);
                if(row==null||row.world()==null||row.body()==null)throw new IllegalStateException("INVALID_EXECUTION_LOG");
                if(loggedWorld!=null&&!loggedWorld.equals(row.world()))throw new IllegalStateException("EXECUTION_LOG_WORLD_CONFLICT");
                loggedWorld=row.world();
                if(row.sequence()<=walSequence)continue;
                walSequence=row.sequence();
                if(!actions.isEmpty()&&!worldId.equals(row.world()))throw new IllegalStateException("EXECUTION_LOG_WORLD_CONFLICT");
                worldId=row.world();generations.merge(row.body(),row.generation(),Math::max);
                var e=row.entry();if(e!=null){
                    var previous=find(row.body(),e.actionId());
                    if(previous!=null&&!previous.fingerprint().equals(e.fingerprint()))throw new IllegalStateException("EXECUTION_LOG_ID_CONFLICT");
                    if(previous==null||!terminal(previous.state())){
                        var restored=new Entry(e.actionId(),e.fingerprint(),e.generation(),terminal(e.state())?e.state():"RECONCILE_REQUIRED",e.result(),e.accessSpent(),e.primaryBroken());
                        actions.computeIfAbsent(row.body(),k->new LinkedHashMap<>()).put(e.actionId(),restored);
                    }
                }
                if(row.facilities()!=null){publicFacilities.clear();publicFacilities.addAll(row.facilities());}
                if(row.structures()!=null){placedStructures.clear();placedStructures.addAll(row.structures());}
            }
        }catch(java.io.IOException|com.google.gson.JsonParseException error){throw new IllegalStateException("EXECUTION_LOG_REQUIRES_RECONCILIATION",error);}
        log=new ExecutionLog(path);setDirty();
    }
    java.util.concurrent.CompletableFuture<Void> persist(UUID body,String action){
        if(log==null)return java.util.concurrent.CompletableFuture.completedFuture(null); // pure journal fixtures
        var row=new Durable(++walSequence,worldId,body,generations.getOrDefault(body,0L),find(body,action),Set.copyOf(publicFacilities),Set.copyOf(placedStructures));
        return log.append(new com.google.gson.Gson().toJson(row));
    }
    java.util.concurrent.CompletableFuture<Void> barrier(){return log==null?java.util.concurrent.CompletableFuture.completedFuture(null):log.barrier();}
    static BackendJournal load(CompoundTag tag){return new BackendJournal(tag);}
    UUID worldId(){return worldId;}
    Set<net.minecraft.core.BlockPos> publicPositions(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        String prefix=dimension.location()+"/";var result=new HashSet<net.minecraft.core.BlockPos>();
        for(var entry:publicFacilities)if(entry.startsWith(prefix))result.add(net.minecraft.core.BlockPos.of(Long.parseLong(entry.substring(prefix.length()))));return result;
    }
    boolean isPublic(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos p){return publicFacilities.contains(dimension.location()+"/"+p.asLong());}
    void publish(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos p){if(publicFacilities.add(dimension.location()+"/"+p.asLong()))setDirty();}
    void unpublish(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos p){if(publicFacilities.remove(dimension.location()+"/"+p.asLong()))setDirty();}
    void recordStructure(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos p){if(placedStructures.add(dimension.location()+"/"+p.asLong()))setDirty();}
    void removedStructure(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos p){unpublish(dimension,p);if(placedStructures.remove(dimension.location()+"/"+p.asLong()))setDirty();}
    boolean structureAt(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,net.minecraft.core.BlockPos p){return placedStructures.contains(dimension.location()+"/"+p.asLong());}
    Set<net.minecraft.core.BlockPos> structures(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        String prefix=dimension.location()+"/";var result=new HashSet<net.minecraft.core.BlockPos>();
        for(var entry:placedStructures)if(entry.startsWith(prefix))result.add(net.minecraft.core.BlockPos.of(Long.parseLong(entry.substring(prefix.length()))));return result;
    }
    long nextGeneration(UUID id) {
        long next=Math.addExact(generations.getOrDefault(id,0L),1L);generations.put(id,next);setDirty();return next;
    }
    Entry find(UUID id,String actionId){return actions.getOrDefault(id,new LinkedHashMap<>()).get(actionId);}
    void begin(UUID id,String actionId,String fingerprint,long generation) {
        if(find(id,actionId)!=null)throw new IllegalStateException("Action already registered");
        actions.computeIfAbsent(id,k->new LinkedHashMap<>()).put(actionId,new Entry(actionId,fingerprint,generation,"RUNNING","",0,0));setDirty();
    }
    void update(UUID id,String actionId,String state,String result,int accessSpent,int primaryBroken) {
        var previous=find(id,actionId);if(previous==null)throw new IllegalStateException("Missing request identity");
        if(terminal(previous.state()))return; // no late acceptance/running/duplicate terminal may rewrite proof
        if(accessSpent<previous.accessSpent()||primaryBroken<previous.primaryBroken())throw new IllegalArgumentException("Mutation count cannot regress");
        if(previous.state().equals(state)&&previous.result().equals(result)&&previous.accessSpent()==accessSpent&&previous.primaryBroken()==primaryBroken)return;
        actions.get(id).put(actionId,new Entry(actionId,previous.fingerprint(),previous.generation(),state,result,accessSpent,primaryBroken));setDirty();
    }
    static boolean terminal(String state){return Set.of("SUCCESS","FAILED","TIMEOUT","CANCELLED").contains(state);}
    @Override public CompoundTag save(CompoundTag output,HolderLookup.Provider provider) {
        output.putLong("walSequence",walSequence);output.putInt("schema",1);output.putUUID("worldId",worldId);var bodies=new ListTag();
        var facilities=new ListTag();for(var entry:publicFacilities)facilities.add(StringTag.valueOf(entry));output.put("publicFacilities",facilities);
        var structures=new ListTag();for(var entry:placedStructures)structures.add(StringTag.valueOf(entry));output.put("placedStructures",structures);
        for(var g:generations.entrySet()) {
            var body=new CompoundTag();body.putUUID("id",g.getKey());body.putLong("generation",g.getValue());var rows=new ListTag();
            for(var entry:actions.getOrDefault(g.getKey(),new LinkedHashMap<>()).values()) {
                var row=new CompoundTag();row.putString("actionId",entry.actionId());row.putString("fingerprint",entry.fingerprint());
                row.putLong("generation",entry.generation());row.putString("state",entry.state());row.putString("result",entry.result());
                row.putInt("accessSpent",entry.accessSpent());row.putInt("primaryBroken",entry.primaryBroken());rows.add(row);
            }
            body.put("actions",rows);bodies.add(body);
        }
        output.put("bodies",bodies);return output;
    }
}
