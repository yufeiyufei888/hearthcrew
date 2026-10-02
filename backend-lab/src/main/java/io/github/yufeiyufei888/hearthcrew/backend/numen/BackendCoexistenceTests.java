package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Narrow real respawn checks, loaded alongside the unchanged installed Changming JAR. */
@GameTestHolder("hearthcrew_backend_coexistence")
@PrefixGameTestTemplate(false)
public class BackendCoexistenceTests {
    @GameTest(template="empty",timeoutTicks=1200,batch="coexistence-drops")
    public static void ownedDeathDropsBedAndNextWork(GameTestHelper h) {
        BackendLifecycleTests.nativeKillDropsAndBedRespawn(h);
    }
    @GameTest(template="empty",timeoutTicks=1200,batch="coexistence-keep")
    public static void ownedKeepInventoryBedAndNextWork(GameTestHelper h) {
        BackendLifecycleTests.nativeKeepInventoryAndBedRespawn(h);
    }
    @GameTest(template="empty",timeoutTicks=100,batch="coexistence-other-bodies")
    public static void vanillaAndChangmingRespawnTypesArePreserved(GameTestHelper h) {
        var level=h.getLevel(); var server=level.getServer();
        // A real local embedded connection supplies the NeoForge channel metadata
        // required by respawn. FakePlayer's unconnected listener cannot do so.
        var transport=BackendGateTests.spawn(h);
        var fake=new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"CoexistVanilla"),ClientInformation.createDefault());
        fake.connection=transport.body().connection;
        var vanilla=server.getPlayerList().respawn(fake,true,Entity.RemovalReason.DISCARDED);
        h.assertTrue(vanilla.getClass()==ServerPlayer.class,"unowned vanilla respawn type is unchanged");
        server.getPlayerList().remove(vanilla);
        try {
            var type=Class.forName("io.github.yufeiyufei888.changming.entity.CompanionEntity");
            var profile=new GameProfile(UUID.randomUUID(),"CoexistChangming");
            var companion=(ServerPlayer)type.getConstructor(net.minecraft.server.MinecraftServer.class,
                ServerLevel.class,GameProfile.class,ClientInformation.class)
                .newInstance(server,level,profile,ClientInformation.createDefault());
            companion.connection=fake.connection;
            var replacement=server.getPlayerList().respawn(companion,true,Entity.RemovalReason.DISCARDED);
            h.assertTrue(replacement.getClass()==type,"Changming's constructor redirect result is preserved");
            h.assertTrue(!NumenBackend.owns(replacement.getUUID()),"HearthCrew did not acquire Changming identity");
            server.getPlayerList().remove(replacement);
        } catch(ReflectiveOperationException error) {
            throw new IllegalStateException("Coexistence fixture requires the installed Changming Mod",error);
        }
        transport.close();h.succeed();
    }
}
