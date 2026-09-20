package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskPersistence;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=TaskPersistence.class,remap=false)
public abstract class OwnedPersistenceMixin {
    @Inject(method="restore",at=@At("HEAD"),cancellable=true)
    private static void noReplay(NumenPlayer p,CallbackInfo ci) {if(NumenBackend.owns(p.getUUID()))ci.cancel();}
}
