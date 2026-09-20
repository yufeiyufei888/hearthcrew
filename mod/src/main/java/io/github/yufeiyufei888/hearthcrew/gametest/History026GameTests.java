package io.github.yufeiyufei888.hearthcrew.gametest;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import io.github.yufeiyufei888.hearthcrew.runtime.*;

@GameTestHolder("hearthcrewnative") @PrefixGameTestTemplate(false)
public class History026GameTests {
 @GameTest(template="p0_empty",timeoutTicks=100,batch="history026")
 public static void thousandsOfReceiptsFitSnapshotWithoutDeletingHistory(GameTestHelper h) throws Exception {
  var json=new Gson();int oldBytes=0;var all=new java.util.ArrayList<CompanionEntity>();
  for(int n=0;n<3;n++){
   h.setBlock(new BlockPos(n*2,0,2),Blocks.STONE);var p=PlayerTestBodies.spawn(h,new BlockPos(n*2,1,2));all.add(p);
   for(int i=0;i<800;i++){
    var id=ActionId.of("fixture-history-"+n+"-"+i);
    p.executor().submit(id.value(),new BodyOrder(BodyOrder.Kind.WAIT,null,null,1),ActionPriority.OWNER);
    p.executor().arbiter().start(id);p.executor().arbiter().finish(id,ActionState.FAILED,"fixture-invalid-stance:"+"历史回执诊断".repeat(36));
   }
   var full=p.executor().arbiter().journal();oldBytes+=json.toJson(full).getBytes(StandardCharsets.UTF_8).length;
   var page=ActionHistoryPage.read(p,Long.MAX_VALUE,64);h.assertTrue(page.get("truncated").getAsBoolean(),"history page explicitly incomplete");
   var next=ActionHistoryPage.read(p,page.get("beforeSequence").getAsLong(),64);
   h.assertTrue(next.getAsJsonArray("entries").size()>0,"older history remains queryable");
   h.assertTrue(p.executor().arbiter().journal().size()==full.size(),"wire projection cannot delete server receipts");
  }
  h.assertTrue(oldBytes>1048576,"fixture reproduces old oversized history");
  var link=new ModLink(h.getLevel().getServer(),java.nio.file.Path.of("unused-history-fixture-pairing"));
  var snapshot=ModLink.class.getDeclaredMethod("snapshot");snapshot.setAccessible(true);
  var view=(JsonObject)snapshot.invoke(link);int bytes=json.toJson(view).getBytes(StandardCharsets.UTF_8).length;
  h.assertTrue(bytes<524288,"current complete state and bounded history fit comfortably inside 1MiB frame: "+bytes);
  System.out.println("HISTORY026 oldHistoryBytes="+oldBytes+" newSnapshotBytes="+bytes);
  h.succeed();
 }
}
