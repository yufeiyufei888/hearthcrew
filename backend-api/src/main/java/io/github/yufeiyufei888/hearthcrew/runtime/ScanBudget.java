package io.github.yufeiyufei888.hearthcrew.runtime;
import net.minecraft.server.MinecraftServer;
/** One shared read-only perception budget per server tick, including all three roles. */
public final class ScanBudget {
 private static MinecraftServer server;private static long tick=Long.MIN_VALUE,start;private static int used;
 public static boolean claim(MinecraftServer current,int checks){long now=current.overworld().getGameTime();if(server!=current||tick!=now){server=current;tick=now;used=0;start=System.nanoTime();}
  if(used+checks>4096||System.nanoTime()-start>=2_000_000)return false;used+=checks;return true;
 }
 public static int used(){return used;}
}
