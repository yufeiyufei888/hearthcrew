package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(PlayerList.class)
public abstract class OwnedRespawnMixin {
    // Replace the new-player local before vanilla restores data and registers it.
    // This composes with other mods' constructor redirects: their result remains
    // unchanged for players outside HearthCrew's ownership.
    // Ordinal 0 is the previous-player argument; ordinal 1 is the replacement local.
    @ModifyVariable(method="respawn",at=@At("STORE"),ordinal=1,require=1)
    private ServerPlayer hearthcrew$nativeBody(ServerPlayer replacement,
            ServerPlayer previous,boolean alive,Entity.RemovalReason reason) {
        return previous instanceof NumenPlayer && NumenBackend.owns(previous.getUUID())
            ? new NumenPlayer(previous.server,replacement.serverLevel(),previous.getGameProfile(),previous.clientInformation())
            : replacement;
    }
}
