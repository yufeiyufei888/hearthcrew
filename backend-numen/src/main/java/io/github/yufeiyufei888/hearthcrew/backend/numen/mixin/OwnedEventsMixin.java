package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adapter-owned bodies must not awaken a second upstream client model loop. */
@Mixin(value=NumenEvents.class,remap=false)
public abstract class OwnedEventsMixin {
    @Inject(method="emit",at=@At("HEAD"),cancellable=true,require=1)
    private static void hearthcrew$ownedEvents(NumenPlayer player,NumenEvents.Kind kind,Map<String,String> attributes,
            String text,boolean urgent,CallbackInfo ci) {
        if(player!=null&&NumenBackend.owns(player.getUUID()))ci.cancel();
    }
}
