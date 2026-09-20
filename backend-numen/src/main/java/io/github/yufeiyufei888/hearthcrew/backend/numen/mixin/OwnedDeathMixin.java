package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep upstream owner-teleport respawn out of our independently owned lifecycle. */
@Mixin(value=Companions.class,remap=false)
public abstract class OwnedDeathMixin {
    @Inject(method="onDeath",at=@At("HEAD"),cancellable=true,require=1)
    private static void hearthcrew$nativeDeath(NumenPlayer player,CallbackInfo ci) {
        if(NumenBackend.handleDeath(player))ci.cancel();
    }
}
