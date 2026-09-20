package io.github.yufeiyufei888.hearthcrew.runtime;

import com.dwinovo.numen.entity.FakeConnection;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Explicit headless test owner. No model, no inventory, no commands or hidden companion. */
final class BackendFixtureOwner {
    static ServerPlayer join(ServerLevel level,Vec3 location){
        var profile=new GameProfile(UUID.randomUUID(),"FixtureOwner");var server=level.getServer();
        var owner=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new FakeConnection(),owner,CommonListenerCookie.createInitial(profile,false));
        owner.setGameMode(GameType.SPECTATOR);owner.moveTo(location.x,location.y,location.z,0,0);
        server.getPlayerList().setViewDistance(6);server.getPlayerList().setSimulationDistance(6);
        return owner;
    }
    static void close(ServerPlayer owner){owner.server.getPlayerList().remove(owner);owner.connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal("headless fixture finished"));}
}
