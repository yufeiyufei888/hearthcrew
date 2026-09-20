package io.github.yufeiyufei888.hearthcrew.mixin;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;

/** Preserve our registered subtype while vanilla performs the complete respawn transaction. */
@Mixin(PlayerList.class)
public abstract class PlayerRespawnMixin {
    @Redirect(method="respawn",at=@At(value="NEW",target="net/minecraft/server/level/ServerPlayer"))
    private ServerPlayer hearthcrew$body(MinecraftServer server,ServerLevel level,GameProfile profile,ClientInformation info,
            ServerPlayer previous,boolean alive,Entity.RemovalReason reason){
        return previous instanceof CompanionEntity?new CompanionEntity(server,level,profile,info):new ServerPlayer(server,level,profile,info);
    }
}
