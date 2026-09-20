package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;

/** Wire projection only: original receipts and save data are never trimmed here. */
public final class ActionHistoryPage {
 private static final Gson JSON=new Gson();
 public static JsonObject read(CompanionEntity body,long before,int limit){
  if(limit<1||limit>64)throw new IllegalArgumentException("history limit must be 1..64");
  var journal=body.executor().arbiter().journal();var rows=new JsonArray();var seen=new HashSet<String>();
  int bytes=2;long cursor=before;boolean more=false;
  for(int i=journal.size()-1;i>=0;i--){
   var receipt=journal.get(i);if(receipt.sequence()>=before)continue;
   if(!seen.add(receipt.id().value()))continue;
   var row=JSON.toJsonTree(receipt).getAsJsonObject();
   row.add("execution",JSON.toJsonTree(body.executor().executionReport(receipt.id().value())));
   row.addProperty("historical",receipt.message().startsWith("historical outcome restored:")||receipt.message().startsWith("saved outcome restored:"));
   int size=JSON.toJson(row).getBytes(StandardCharsets.UTF_8).length;
   if(rows.size()>=limit||bytes+size>65536){more=true;break;}
   rows.add(row);bytes+=size+1;cursor=receipt.sequence();
  }
  // Existing consumers resolve the last receipt per identity: keep ascending order.
  var ascending=new JsonArray();for(int i=rows.size()-1;i>=0;i--)ascending.add(rows.get(i));
  var result=new JsonObject();result.add("entries",ascending);result.addProperty("truncated",more);
  result.addProperty("retainedReceiptCount",journal.size());result.addProperty("beforeSequence",cursor);
  if(more&&rows.isEmpty())result.addProperty("error","HISTORY_ENTRY_TOO_LARGE: record retained on server; not silently skipped");
  return result;
 }
}
