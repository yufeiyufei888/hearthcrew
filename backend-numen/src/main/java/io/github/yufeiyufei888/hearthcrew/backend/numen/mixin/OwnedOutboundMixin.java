package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import com.dwinovo.numen.entity.FakeConnection;
import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The upstream one-argument guard does not cover mods calling send(packet, listener) directly. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class OwnedOutboundMixin {
    @Shadow @Final protected Connection connection;
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",at=@At("HEAD"),cancellable=true)
    private void hearthcrew$dropClientlessPacket(CallbackInfo ci) {
        if(connection instanceof FakeConnection&&(Object)this instanceof ServerGamePacketListenerImpl listener&&NumenBackend.owns(listener.player.getUUID()))ci.cancel();
    }
}
