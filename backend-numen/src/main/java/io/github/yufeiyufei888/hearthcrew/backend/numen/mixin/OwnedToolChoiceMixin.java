package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.core.act.ToolSelect;
import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ToolSelect.class,remap=false)
public abstract class OwnedToolChoiceMixin {
    @Inject(method="holdBestTool",at=@At("HEAD"),cancellable=true)
    private static void chooseActualUsableReplacement(NumenPlayer body,BlockState state,CallbackInfo ci){
        if(NumenBackend.owns(body.getUUID())){ToolChoice.hold(body,state);ci.cancel();}
    }
}
