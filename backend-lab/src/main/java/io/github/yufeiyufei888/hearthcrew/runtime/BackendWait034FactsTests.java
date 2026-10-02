package io.github.yufeiyufei888.hearthcrew.runtime;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.backend.numen.*;
@GameTestHolder("hearthcrew_backend_wait034facts")
@PrefixGameTestTemplate(false)
public final class BackendWait034FactsTests {
 @GameTest(template="empty",timeoutTicks=1600,batch="034-scan-facts")
 public static void refreshVersionIsNotResourceOrPathChange(GameTestHelper h){
  BackendProtection.configure(h.getLevel().getServer(),level->java.util.Set.of());
  for(int x=0;x<7;x++)for(int z=2;z<9;z++)h.setBlock(new BlockPos(x,0,z),Blocks.STONE);
  var p=h.absolutePos(new BlockPos(3,1,5));
  var server=h.getLevel().getServer();var owner=new net.minecraft.server.level.ServerPlayer(server,h.getLevel(),new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"ScanFixtureOwner"),net.minecraft.server.level.ClientInformation.createDefault());
  owner.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.join(owner,"Ember");
  var b=io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.live(server).getFirst();NearbyObservation.clear();
  var wall=b.body().blockPosition().offset(1,0,0);h.getLevel().setBlockAndUpdate(wall,Blocks.BEDROCK.defaultBlockState());
  int[] phase={0};long[] revision={0},terrain={0};String[] resource={""},origin={""};
  h.onEachTick(()->{
   NearbyObservation.scan(b.body(),2);NearbyObservation.tick(b.body().server);
   long current=NearbyObservation.revision(b.body());if(current<=revision[0])return;
   if(phase[0]==0){resource[0]=NearbyObservation.resourceFacts(b.body());terrain[0]=NearbyObservation.terrainFacts(b.body());origin[0]=NearbyObservation.terrainOrigin(b.body());}
   else if(phase[0]==1){
    h.assertTrue(resource[0].equals(NearbyObservation.resourceFacts(b.body()))&&terrain[0]==NearbyObservation.terrainFacts(b.body()),"identical refreshed scan keeps stable facts despite new revision");
    h.getLevel().setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());
   }else{
    h.assertTrue(resource[0].equals(NearbyObservation.resourceFacts(b.body())),"non-resource wall removal is not a new resource");
    h.assertTrue(origin[0].equals(NearbyObservation.terrainOrigin(b.body()))&&terrain[0]!=NearbyObservation.terrainFacts(b.body()),"actual terrain change at the same origin can wake a path wait");
    NearbyObservation.clear();b.close();h.succeed();return;
   }
   revision[0]=current;phase[0]++;
  });
 }
}
