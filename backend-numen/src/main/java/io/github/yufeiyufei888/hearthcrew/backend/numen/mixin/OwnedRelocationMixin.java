package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Every native relocation unwinds even when a callback or the native entry throws. */
@Mixin(ServerPlayer.class)
public abstract class OwnedRelocationMixin {
    @Unique private int hearthcrew$relocationDepth;
    @Unique private <T> T hearthcrew$relocate(java.util.function.Supplier<T> operation) {
        Throwable failure=null;
        try {
            if(hearthcrew$relocationDepth++==0)NumenBackend.relocating((ServerPlayer)(Object)this);
            return operation.get();
        } catch(RuntimeException|Error error){failure=error;throw error;}
        finally {
            if(--hearthcrew$relocationDepth==0&&NumenBackend.owns(((ServerPlayer)(Object)this).getUUID())) {
                try {var p=(ServerPlayer)(Object)this;p.serverLevel().getChunkSource().move(p);}
                catch(RuntimeException|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
            }
        }
    }
    @WrapMethod(method="teleportTo(DDD)V")
    private void absolute(double x,double y,double z,Operation<Void> original){hearthcrew$relocate(()->original.call(x,y,z));}
    @WrapMethod(method="teleportRelative(DDD)V")
    private void relative(double x,double y,double z,Operation<Void> original){hearthcrew$relocate(()->original.call(x,y,z));}
    @WrapMethod(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V")
    private void level(net.minecraft.server.level.ServerLevel level,double x,double y,double z,float yaw,float pitch,Operation<Void> original){
        hearthcrew$relocate(()->original.call(level,x,y,z,yaw,pitch));
    }
    @WrapMethod(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")
    private boolean command(net.minecraft.server.level.ServerLevel level,double x,double y,double z,java.util.Set<net.minecraft.world.entity.RelativeMovement> flags,float yaw,float pitch,Operation<Boolean> original){
        return hearthcrew$relocate(()->original.call(level,x,y,z,flags,yaw,pitch));
    }
    @WrapMethod(method="changeDimension")
    private net.minecraft.world.entity.Entity dimension(net.minecraft.world.level.portal.DimensionTransition transition,Operation<net.minecraft.world.entity.Entity> original){
        return hearthcrew$relocate(()->original.call(transition));
    }
}
