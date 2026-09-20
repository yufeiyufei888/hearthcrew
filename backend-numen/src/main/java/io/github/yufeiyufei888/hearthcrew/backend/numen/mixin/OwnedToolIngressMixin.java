package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.network.payload.TaskResultPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.level.ServerPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ExecuteToolPayload.class,remap=false)
public abstract class OwnedToolIngressMixin {
    @Inject(method="handle",at=@At("HEAD"),cancellable=true)
    private static void ownIngress(ExecuteToolPayload request,ServerPlayer sender,CallbackInfo ci) {
        if(!NumenBackend.owns(request.entityUuid()))return;
        // Do not use upstream replyError: it logs full tool arguments.
        Services.NETWORK.sendToPlayer(sender,new TaskResultPayload(request.entityUuid(),request.toolCallId(),
            TaskResult.fail("HEARTHCREW_AUTHORITY_REQUIRED").toJson()));ci.cancel();
    }
}
