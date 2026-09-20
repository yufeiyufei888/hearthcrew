package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.authlib.GameProfile;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(PlayerList.class)
public abstract class OwnedRespawnMixin {
    @Redirect(method="respawn",at=@At(value="NEW",target="net/minecraft/server/level/ServerPlayer"),require=1)
    private ServerPlayer hearthcrew$nativeBody(MinecraftServer server,ServerLevel level,GameProfile profile,
            ClientInformation info,ServerPlayer previous,boolean alive,Entity.RemovalReason reason) {
        return previous instanceof NumenPlayer && NumenBackend.owns(previous.getUUID())
            ? new NumenPlayer(server,level,profile,info) : new ServerPlayer(server,level,profile,info);
    }
}
