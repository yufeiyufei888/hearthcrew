package io.github.yufeiyufei888.hearthcrew.mixin;

import io.github.yufeiyufei888.hearthcrew.entity.LocalPlayerConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Companion endpoints have no remote client. Consume outbound presentation packets
 * before NeoForge checks channels negotiated with real clients. Real connections
 * retain the complete vanilla/NeoForge path, including authentication and checks. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class LocalPlayerPacketMixin {
    @Shadow @Final protected Connection connection;

    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at=@At("HEAD"), cancellable=true)
    private void hearthcrew$localOutbound(Packet<?> packet, PacketSendListener listener, CallbackInfo callback) {
        if (connection instanceof LocalPlayerConnection) callback.cancel();
    }
}
