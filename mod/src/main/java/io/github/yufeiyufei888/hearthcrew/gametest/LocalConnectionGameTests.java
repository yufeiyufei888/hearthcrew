package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder("hearthcrewjoin")
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class LocalConnectionGameTests {
    @GameTest(template="p0_empty",timeoutTicks=100)
    public static void loginPayloadAndRepeatJoin(GameTestHelper helper) {
        for(int x=0;x<12;x++)for(int z=0;z<12;z++){
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
            for(int y=1;y<5;y++)helper.setBlock(new BlockPos(x,y,z),Blocks.AIR);
        }
        java.util.function.Consumer<PlayerEvent.PlayerLoggedInEvent> listener=event->{
            if(event.getEntity() instanceof CompanionEntity p)p.connection.send(new ClientboundCustomPayloadPacket(
                    new DiscardedPayload(ResourceLocation.fromNamespaceAndPath("hearthcrew_fixture","login_client_ping"))));
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            var owner=helper.makeMockServerPlayerInLevel();owner.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(5.5,1,5.5)));
            var store=CrewPlayers.get(helper.getLevel().getServer());
            helper.assertTrue(store.addMissingFor(owner)==3,"one button initializes all three despite unnegotiated login payloads");
            helper.assertTrue(store.addMissingFor(owner)==0,"second press does not duplicate players");
            for(String name:new String[]{"Ember","Moss","Flint"}){
                var player=helper.getLevel().getServer().getPlayerList().getPlayerByName(name);
                helper.assertTrue(player instanceof CompanionEntity&&player.isAlive(),"native registered player: "+name);
                helper.assertTrue(player.getInventory().isEmpty(),"empty inventory after new roster join");
            }
            helper.succeed();
        } finally {NeoForge.EVENT_BUS.unregister(listener);}
    }
}
