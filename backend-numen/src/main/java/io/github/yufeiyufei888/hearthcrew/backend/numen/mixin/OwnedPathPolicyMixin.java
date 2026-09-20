package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.core.pathing.moves.*;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import io.github.yufeiyufei888.hearthcrew.backend.numen.TerrainSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Pin-specific bridge of per-body permissions into immutable upstream search contexts. */
@Mixin(value=CalculationContext.class,remap=false)
public abstract class OwnedPathPolicyMixin implements io.github.yufeiyufei888.hearthcrew.backend.numen.OwnedSearchContext {
    @Shadow @Final public ServerPlayer player;
    @Shadow @Final public net.minecraft.world.level.BlockGetter view;
    @Shadow @Final @Mutable public boolean allowDownward;
    @Unique private NumenBackend.PathPolicy hearthcrew$policy;
    @Unique private io.github.yufeiyufei888.hearthcrew.backend.numen.SearchBudget hearthcrew$budget;
    public io.github.yufeiyufei888.hearthcrew.backend.numen.SearchBudget hearthcrew$searchBudget(){return hearthcrew$budget;}
    @Inject(method="<init>(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/BlockGetter;Lcom/dwinovo/numen/core/pathing/moves/ChunkLoadedTest;ZLit/unimi/dsi/fastutil/longs/LongSet;Lit/unimi/dsi/fastutil/longs/LongSet;Lcom/dwinovo/numen/core/pathing/moves/TerrainPermit;)V",at=@At("RETURN"),require=1)
    private void hearthcrew$snapshot(CallbackInfo ci) {
        long began=System.nanoTime();
        hearthcrew$policy=NumenBackend.pathPolicy(player);
        hearthcrew$budget=NumenBackend.searchBudget(player);
        io.github.yufeiyufei888.hearthcrew.backend.numen.SlowOperations.record("path_policy_snapshot",began);
        if(hearthcrew$policy!=null)allowDownward=false; // descend by steps; never dig straight under our feet
    }
    @Inject(method="breakCostMultiplierAt",at=@At("HEAD"),cancellable=true,require=1)
    private void hearthcrew$breakCost(int x,int y,int z,BlockState state,CallbackInfoReturnable<Double> ci) {
        var position=new BlockPos(x,y,z);
        if(hearthcrew$policy!=null&&(!hearthcrew$policy.permitsBreak(position,state)||!TerrainSafety.dryStableCell(view,position)))ci.setReturnValue(ActionCosts.COST_INF);
    }
    @Inject(method="costOfPlacingAt",at=@At("HEAD"),cancellable=true,require=1)
    private void hearthcrew$noImplicitBridge(int x,int y,int z,BlockState state,CallbackInfoReturnable<Double> ci) {
        if(hearthcrew$policy!=null)ci.setReturnValue(ActionCosts.COST_INF);
    }
}
