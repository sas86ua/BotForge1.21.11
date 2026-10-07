package com.minebot.bot;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

/**
 * A connection with no network channel behind it. Everything the server tries
 * to send to the bot is dropped, and the pipeline setup calls are no-ops.
 */
public class BotConnection extends Connection {
    // Forge keeps per-connection state in channel attributes, so a channel must exist.
    // Nothing is ever written to it.
    private final Channel channel = new EmbeddedChannel();

    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }

    @Override
    public void handleDisconnection() {
    }
}
