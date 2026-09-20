package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.entity.NumenPlayer;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=PlayerNav.class,remap=false)
public abstract class OwnedNavigationResultMixin {
 @Shadow @Final private NumenPlayer player;
 @Shadow private net.minecraft.core.BlockPos plannedCenter;
 @Shadow @Final private com.dwinovo.numen.core.pathing.execute.PathingCore core;
 @Unique private double hearthcrew$best=Double.MAX_VALUE;
 @Unique private int hearthcrew$idle,hearthcrew$replans;
 @Shadow private PlayerNav.Status fail(com.dwinovo.numen.core.FailureType type,String reason){throw new AssertionError();}
 @Inject(method="tick",at=@At("RETURN"),cancellable=true,require=1)
 private void hearthcrew$progress(CallbackInfoReturnable<PlayerNav.Status> ci){
  if(!NumenBackend.owns(player.getUUID())||ci.getReturnValue()!=PlayerNav.Status.RUNNING||plannedCenter==null)return;
  NumenBackend.navigationTarget(player,plannedCenter);
  if(((PlayerNav)(Object)this).planningInFlight()||core.getCurrent()==null)return;
  double d=player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(plannedCenter));
  if(d<hearthcrew$best-.5){hearthcrew$best=d;hearthcrew$idle=0;}else hearthcrew$idle++;
  if(hearthcrew$idle>=100){
   hearthcrew$idle=0;
   if(hearthcrew$replans++==0)core.forceCancel();
   else ci.setReturnValue(fail(com.dwinovo.numen.core.FailureType.NO_PATH,"MOVEMENT_STALLED_AFTER_REPLAN"));
  }
 }
 @Inject(method="fail",at=@At("RETURN"),require=1)
 private void hearthcrew$failure(CallbackInfoReturnable<PlayerNav.Status> ci){
  NumenBackend.navigationFailed(player,((PlayerNav)(Object)this).failReason());
 }
}
