package io.github.yufeiyufei888.hearthcrew.runtime;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;
/** Shared protocol/action-capabilities.json is the single parameter and capability definition. */
public final class ActionCapabilities {
 private static final JsonObject DEFINITIONS=load();
 private static JsonObject load(){try(var stream=ActionCapabilities.class.getResourceAsStream("/hearthcrew/action-capabilities.json")){
  if(stream==null)throw new IllegalStateException("capability resource missing");return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
 }catch(java.io.IOException e){throw new IllegalStateException(e);}}
 public static JsonObject definitions(){return DEFINITIONS.deepCopy();}
 public static void validate(String kind,BlockPos pos,UUID target,int count,ResourceLocation resource,JsonObject raw){
  if(!DEFINITIONS.has(kind))throw new IllegalArgumentException("UNIMPLEMENTED_ACTION");var rule=DEFINITIONS.getAsJsonObject(kind);
  var values=new java.util.HashMap<String,Object>();if(pos!=null)values.put("position",pos);if(target!=null)values.put("target",target);if(resource!=null)values.put("resource",resource);
  for(String field:java.util.List.of("steps","actions","candidates","radius","accessBudget"))if(raw.has(field))values.put(field,raw.get(field));if(raw.has("count")||raw.has("slot"))values.put("count",count);
  var allowed=new java.util.HashSet<String>();rule.getAsJsonArray("allowed").forEach(v->allowed.add(v.getAsString()));
  for(var key:values.keySet())if(!allowed.contains(key))throw new IllegalArgumentException(kind+" does not accept "+key);
  for(var key:rule.getAsJsonArray("required"))if(!values.containsKey(key.getAsString()))throw new IllegalArgumentException(kind+" requires "+key.getAsString());
  if(kind.equals("INTERACT")&&(pos==null)==(target==null))throw new IllegalArgumentException("INTERACT requires position or target exclusively");
  if(count<rule.get("minCount").getAsInt()||count>rule.get("maxCount").getAsInt())throw new IllegalArgumentException(kind+" count outside supported bounds");
 }
}
