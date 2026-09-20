package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.*;
import com.google.gson.JsonObject;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Consumer;

@Mixin(value=TaskDispatch.class,remap=false)
public abstract class OwnedDispatchMixin {
    @Inject(method="runSync",at=@At("HEAD"),cancellable=true)
    private static void sync(NumenPlayer p,TaskRecord r,Consumer<String> reply,CallbackInfo ci) {deny(p,reply,ci);}
    @Inject(method="setTask",at=@At("HEAD"),cancellable=true)
    private static void async(NumenPlayer p,TaskRecord r,JsonObject args,Consumer<String> reply,CallbackInfo ci) {deny(p,reply,ci);}
    private static void deny(NumenPlayer p,Consumer<String> reply,CallbackInfo ci) {
        if(!NumenBackend.owns(p.getUUID()))return;
        reply.accept(TaskResult.fail("HEARTHCREW_AUTHORITY_REQUIRED: submit through the owning backend").toJson());ci.cancel();
    }
}
