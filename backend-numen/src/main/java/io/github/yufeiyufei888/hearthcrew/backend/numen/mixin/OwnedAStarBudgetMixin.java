package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.core.pathing.astar.*;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import io.github.yufeiyufei888.hearthcrew.backend.numen.OwnedSearchContext;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

/** Pin-specific scheduling adapter; no upstream algorithm copied or global setting changed. */
@Mixin(value=AStarPathFinder.class,remap=false)
public abstract class OwnedAStarBudgetMixin {
    @Shadow @Final private CalculationContext calcContext;
    @Unique private long hearthcrew$waitingNanos;
    @Redirect(method="calculate0",at=@At(value="INVOKE",target="Lcom/dwinovo/numen/core/pathing/astar/BinaryHeapOpenSet;isEmpty()Z"),require=1)
    private boolean hearthcrew$frontier(BinaryHeapOpenSet queue){boolean empty=queue.isEmpty();var b=((OwnedSearchContext)calcContext).hearthcrew$searchBudget();if(b!=null)b.frontier(empty);return empty;}
    @Redirect(method="calculate0",at=@At(value="INVOKE",target="Lcom/dwinovo/numen/core/pathing/moves/CalculationContext;isLoaded(II)Z"),require=1)
    private boolean hearthcrew$boundary(CalculationContext context,int x,int z){boolean loaded=context.isLoaded(x,z);var b=((OwnedSearchContext)calcContext).hearthcrew$searchBudget();if(!loaded&&b!=null)b.boundary();return loaded;}
    @Inject(method="calculate0",at=@At("HEAD"),require=1)
    private void hearthcrew$checkFrozen(long primary,long failure,CallbackInfoReturnable<Optional<NavPath>> ci) {
        if(((OwnedSearchContext)calcContext).hearthcrew$searchBudget()!=null&&!calcContext.safeForThreadedUse)
            throw new IllegalStateException("Owned path search requires a frozen worker context");
        var b=((OwnedSearchContext)calcContext).hearthcrew$searchBudget();if(b!=null)b.started();
    }
    @Inject(method="calculate0",at=@At("RETURN"),require=1)
    private void hearthcrew$finished(long primary,long failure,CallbackInfoReturnable<Optional<NavPath>> ci){var b=((OwnedSearchContext)calcContext).hearthcrew$searchBudget();if(b!=null)b.finished(ci.getReturnValue().isPresent());}
    @Redirect(method="calculate0",at=@At(value="FIELD",target="Lcom/dwinovo/numen/core/pathing/settings/NavSettings;maxNodesPerSearch:I"),require=1)
    private int hearthcrew$nodeLimit(NavSettings settings) {
        return ((OwnedSearchContext)calcContext).hearthcrew$searchBudget()==null?settings.maxNodesPerSearch:Math.min(16384,settings.maxNodesPerSearch);
    }
    @Inject(method="calculate0",at=@At(value="INVOKE",target="Lcom/dwinovo/numen/core/pathing/astar/BinaryHeapOpenSet;removeLowest()Lcom/dwinovo/numen/core/pathing/astar/PathNode;"),cancellable=true,require=1)
    private void hearthcrew$perTickCredit(long primary,long failure,CallbackInfoReturnable<Optional<NavPath>> ci) {
        var budget=((OwnedSearchContext)calcContext).hearthcrew$searchBudget();if(budget==null)return;
        long began=System.nanoTime();boolean accepted=budget.claim();hearthcrew$waitingNanos+=System.nanoTime()-began;
        if(!accepted){((AStarPathFinder)(Object)this).cancel();ci.setReturnValue(Optional.empty());}
    }
    @Redirect(method="calculate0",at=@At(value="INVOKE",target="Ljava/lang/System;currentTimeMillis()J"),require=2)
    private long hearthcrew$computeClock() {
        // Waiting for next game-tick credit must not consume the algorithm's compute deadline.
        return System.currentTimeMillis()-hearthcrew$waitingNanos/1_000_000;
    }
}
