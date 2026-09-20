package io.github.yufeiyufei888.hearthcrew.entity;

import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A local synthetic endpoint only for registered companions; never authenticates network clients. */
public final class LocalPlayerConnection extends Connection {
    private boolean open = true;
    private final io.netty.channel.embedded.EmbeddedChannel endpoint = new io.netty.channel.embedded.EmbeddedChannel(this);
    @Override public io.netty.channel.Channel channel(){return endpoint;}
    @Override public java.net.SocketAddress getRemoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",0);}
    public LocalPlayerConnection() { super(PacketFlow.SERVERBOUND); }
    @Override public void send(Packet<?> packet) { }
    @Override public void send(Packet<?> packet, PacketSendListener listener) { }
    @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { }
    @Override public boolean isConnected() { return open; }
    @Override public boolean isConnecting() { return false; }
    @Override public void disconnect(Component reason) { open=false;endpoint.finishAndReleaseAll(); }
    @Override public void disconnect(DisconnectionDetails reason) { open=false;endpoint.finishAndReleaseAll(); }
    @Override public void tick() { }
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol,T listener) { }
}
