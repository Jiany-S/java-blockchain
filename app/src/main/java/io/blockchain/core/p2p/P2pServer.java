package io.blockchain.core.p2p;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;

import java.util.logging.Logger;

public class P2pServer {
    private static final Logger LOG = Logger.getLogger(P2pServer.class.getName());
    private final int port;
    private Channel serverChannel;

    public P2pServer(int port) { this.port = port; }

    public void start() {
        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<>() {
                 @Override
                 protected void initChannel(Channel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast(new ByteArrayDecoder(), new ByteArrayEncoder(), new SimpleHandler());
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            serverChannel = f.channel();
            LOG.info("P2P server listening on port " + port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close();
    }

    private static class SimpleHandler extends SimpleChannelInboundHandler<byte[]> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
            LOG.info("Received " + msg.length + " bytes");
        }
    }
}
