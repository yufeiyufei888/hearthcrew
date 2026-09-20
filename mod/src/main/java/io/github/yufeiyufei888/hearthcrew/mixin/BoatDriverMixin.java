package io.github.yufeiyufei888.hearthcrew.mixin;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Apply vanilla boat propulsion server-side only for our own driver, once after native flotation. */
@Mixin(Boat.class)
public abstract class BoatDriverMixin extends Entity {
 protected BoatDriverMixin(EntityType<?> type,Level level){super(type,level);}
 @Shadow private void controlBoat(){throw new AssertionError();}
 @Override public boolean isControlledByLocalInstance(){return !level().isClientSide()&&getControllingPassenger() instanceof CompanionEntity||super.isControlledByLocalInstance();}
 @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/vehicle/Boat;floatBoat()V",shift=At.Shift.AFTER))
 private void driveLocally(CallbackInfo ci){if(!level().isClientSide()&&getControllingPassenger() instanceof CompanionEntity)controlBoat();}
}
