package io.github.yufeiyufei888.hearthcrew.gametest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers;
public final class PlayerTestBodies {
 public static CompanionEntity spawn(GameTestHelper helper, BlockPos position){
  var body=CrewPlayers.create(helper.getLevel());var p=helper.absolutePos(position);
  body.teleportTo(helper.getLevel(),p.getX()+.5,p.getY(),p.getZ()+.5,0,0);
  body.setRespawnEnabled(false);body.inventory().clearContent();return body;
 }
}
