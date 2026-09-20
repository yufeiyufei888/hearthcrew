package io.github.yufeiyufei888.hearthcrew.backend.numen;
import java.util.*;
/** Bounded aggregate timings; emits no inventories, conversation, paths or credentials. */
public final class SlowOperations {
 private static final Map<String,long[]> samples=new HashMap<>();
 public static synchronized void record(String operation,long began){
  long elapsed=System.nanoTime()-began,now=System.nanoTime();
  var row=samples.computeIfAbsent(operation,k->new long[5]);row[0]++;row[1]+=elapsed;row[2]=Math.max(row[2],elapsed);
  if(elapsed>10_000_000L){row[3]++;if(now-row[4]>20_000_000_000L){System.out.println("HEARTHCREW_SLOW operation="+operation+" maxMs="+row[2]/1_000_000+" slowCount="+row[3]);row[4]=now;}}
 }
 static synchronized Map<String,Object> snapshot(){var result=new LinkedHashMap<String,Object>();samples.forEach((k,v)->result.put(k,Map.of("calls",v[0],"totalMicros",v[1]/1000,"maxMicros",v[2]/1000,"slowCount",v[3])));return result;}
}
