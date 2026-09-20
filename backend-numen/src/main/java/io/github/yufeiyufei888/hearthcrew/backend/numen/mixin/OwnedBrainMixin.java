package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Upstream empty slots must not clear owned task/reflex state or publish a second brain's status. */
@Mixin(targets="com.dwinovo.numen.task.CompanionBrain",remap=false)
public abstract class OwnedBrainMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void ownedTick(NumenPlayer body,CallbackInfo ci) {
        if(NumenBackend.tickManaged(body))ci.cancel();
    }
}
