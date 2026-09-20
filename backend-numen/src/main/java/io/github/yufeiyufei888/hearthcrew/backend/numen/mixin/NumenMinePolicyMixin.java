package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.mine.MineCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Pinned integration hook: decisions and native break guards use the same scope. */
@Mixin(value=MineCompanionTask.class,remap=false)
public abstract class NumenMinePolicyMixin extends AbstractCompanionTask<MineBlockTaskRecord> {
    protected NumenMinePolicyMixin(NumenPlayer player,MineBlockTaskRecord record){super(player,record);}
    @Shadow @Final private List<BlockPos> knownOres;
    @Shadow @Final private BlockDigger digger;
    @Inject(method={"runQuery","prune"},at=@At("RETURN"),require=2)
    private void hearthcrew$filterCandidates(CallbackInfo ci) {
        if(NumenBackend.owns(player.getUUID())){
            knownOres.removeIf(p->!NumenBackend.miningCandidateAllowed(player,p));
            NumenBackend.selectMiningCandidates(player,knownOres);
        }
    }
    @Inject(method="mineProgress",at=@At("HEAD"),cancellable=true,require=1)
    private void hearthcrew$recheckBeforeDigging(BlockPos target,CallbackInfo ci) {
        if(!NumenBackend.miningCandidateAllowed(player,target)) {
            digger.cancel();knownOres.remove(target);ci.cancel();
        }
    }
}
