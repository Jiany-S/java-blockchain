package io.blockchain.core.p2p;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class P2pServer {
    public interface PeerListener {
        void onPeerConnected(Peer peer);
        void onPeerDisconnected(Peer peer);
        void onMessage(Peer peer, P2pMessage message);
    }

    public record Peer(String nodeId, String remoteAddress) {}

    private static final Logger LOG = Logger.getLogger(P2pServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AttributeKey<PeerContext> CTX_KEY = AttributeKey.valueOf("peer-context");

    private static final long DEFAULT_PING_INTERVAL_MS = 10_000L;
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 30_000L;

    private final String nodeId;
    private final int port;
    private final PeerListener listener;
    private final long pingIntervalMillis;
    private final long idleTimeoutMillis;
    private final boolean autoRespondPings;

    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
    private final NioEventLoopGroup clientGroup = new NioEventLoopGroup();
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Map<String, PeerContext> peersById = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<P2pMessage> periodicMessages = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Supplier<P2pMessage>> periodicSuppliers = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService housekeeping;
    private Channel serverChannel;

    public P2pServer(String nodeId, int port) {
        this(nodeId, port, new LoggingPeerListener());
    }

    public P2pServer(String nodeId, int port, PeerListener listener) {
        this(nodeId, port, listener, DEFAULT_PING_INTERVAL_MS, DEFAULT_IDLE_TIMEOUT_MS, true);
    }

    public P2pServer(String nodeId, int port, PeerListener listener, long pingIntervalMillis, long idleTimeoutMillis, boolean autoRespondPings) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.port = port;
        this.listener = listener == null ? new LoggingPeerListener() : listener;
        this.pingIntervalMillis = Math.max(100L, pingIntervalMillis);
        this.idleTimeoutMillis = Math.max(this.pingIntervalMillis, idleTimeoutMillis);
        this.autoRespondPings = autoRespondPings;
    }

    public void registerPeriodicMessage(P2pMessage message) {
        if (message == null) {
            return;
        }
        periodicMessages.addIfAbsent(message);
    }

    /**
     * Register a supplier that will be invoked on each housekeeping tick to produce
     * a message to broadcast. If the supplier returns null, nothing is sent for that supplier.
     */
    public void registerPeriodicSupplier(Supplier<P2pMessage> supplier) {
        if (supplier == null) {
            return;
        }
        periodicSuppliers.addIfAbsent(supplier);
    }

    public void start() {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            configurePipeline(ch.pipeline());
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            channels.add(serverChannel);
            LOG.info(() -> "P2P server listening on port " + port + " (nodeId=" + nodeId + ")");
            startHousekeeping();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting P2P server", e);
        }
    }

    public void connect(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        String[] parts = endpoint.split(":", 2);
        if (parts.length != 2) {
            LOG.warning(() -> "Invalid peer endpoint: " + endpoint);
            return;
        }
        String host = parts[0].trim();
        int targetPort;
        try {
            targetPort = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            LOG.warning(() -> "Invalid peer port in endpoint: " + endpoint);
            return;
        }
        connect(host, targetPort);
    }

    public void connect(Collection<String> endpoints) {
        if (endpoints == null) {
            return;
        }
        for (String endpoint : endpoints) {
            connect(endpoint);
        }
    }

    public void connect(String host, int targetPort) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configurePipeline(ch.pipeline());
                    }
                });

        bootstrap.connect(host, targetPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel channel = future.channel();
                channels.add(channel);
                LOG.info(() -> "Connected to peer " + host + ':' + targetPort);
            } else {
                LOG.log(Level.WARNING, "Failed to connect to peer " + host + ':' + targetPort, future.cause());
            }
        });
    }

    public void broadcast(P2pMessage message) {
        if (message == null) {
            return;
        }
        for (PeerContext context : peersById.values()) {
            context.channel.writeAndFlush(message);
        }
    }

    public Collection<Peer> peers() {
        List<Peer> peers = new ArrayList<>();
        for (PeerContext context : peersById.values()) {
            peers.add(new Peer(context.nodeId, remoteAddress(context.channel)));
        }
        return peers;
    }

    public void stop() {
        stopHousekeeping();
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        channels.close().awaitUninterruptibly();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        clientGroup.shutdownGracefully();
        peersById.clear();
        LOG.info("P2P server stopped");
    }

    private void startHousekeeping() {
        stopHousekeeping();
        housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "p2p-heartbeat-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        housekeeping.scheduleAtFixedRate(this::runHousekeeping, pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void stopHousekeeping() {
        if (housekeeping != null) {
            housekeeping.shutdownNow();
            housekeeping = null;
        }
    }

    private void runHousekeeping() {
        try {
            long now = System.currentTimeMillis();
            for (PeerContext context : peersById.values()) {
                Channel channel = context.channel;
                if (channel == null || !channel.isActive()) {
                    continue;
                }
                if (context.nodeId == null) {
                    continue;
                }
                if (context.lastSeen > 0 && now - context.lastSeen > idleTimeoutMillis) {
                    LOG.fine(() -> "Closing stale peer " + context.nodeId);
                    channel.close();
                    continue;
                }
                if (now - context.lastPingSent >= pingIntervalMillis) {
                    context.lastPingSent = now;
                    channel.writeAndFlush(P2pMessage.ping());
                }
            }
            for (P2pMessage message : periodicMessages) {
                for (PeerContext context : peersById.values()) {
                    Channel channel = context.channel;
                    if (channel == null || !channel.isActive() || context.nodeId == null) {
                        continue;
                    }
                    channel.writeAndFlush(message);
                }
            }
            for (Supplier<P2pMessage> supplier : periodicSuppliers) {
                P2pMessage dynamic = null;
                try { dynamic = supplier.get(); } catch (Exception ignored) {}
                if (dynamic == null) continue;
                for (PeerContext context : peersById.values()) {
                    Channel channel = context.channel;
                    if (channel == null || !channel.isActive() || context.nodeId == null) {
                        continue;
                    }
                    channel.writeAndFlush(dynamic);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "P2P housekeeping failed", e);
        }
    }

    private void configurePipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new LengthFieldBasedFrameDecoder(1 << 16, 0, 4, 0, 4));
        pipeline.addLast(new LengthFieldPrepender(4));
        pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
        pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
        pipeline.addLast(new JsonCodec());
        pipeline.addLast(new PeerChannelHandler());
    }

    private final class PeerChannelHandler extends SimpleChannelInboundHandler<P2pMessage> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            PeerContext context = new PeerContext(ctx.channel());
            ctx.channel().attr(CTX_KEY).set(context);
            channels.add(ctx.channel());
            ctx.writeAndFlush(P2pMessage.handshake(nodeId));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            PeerContext context = ctx.channel().attr(CTX_KEY).get();
            if (context != null && context.nodeId != null) {
                peersById.remove(context.nodeId);
                listener.onPeerDisconnected(new Peer(context.nodeId, remoteAddress(ctx.channel())));
            }
            channels.remove(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, P2pMessage msg) {
            PeerContext context = ctx.channel().attr(CTX_KEY).get();
            if (context == null) {
                return;
            }
            if ("handshake".equals(msg.type())) {
                Object nodeIdObj = msg.payload().get("nodeId");
                if (nodeIdObj instanceof String id) {
                    context.nodeId = id;
                    context.lastSeen = System.currentTimeMillis();
                    context.lastPingSent = 0L;
                    peersById.put(id, context);
                    listener.onPeerConnected(new Peer(id, remoteAddress(ctx.channel())));
                }
            } else if (context.nodeId != null) {
                context.lastSeen = System.currentTimeMillis();
                if ("ping".equals(msg.type())) {
                    if (autoRespondPings) {
                        ctx.writeAndFlush(P2pMessage.pong());
                    }
                }
                listener.onMessage(new Peer(context.nodeId, remoteAddress(ctx.channel())), msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.WARNING, "P2P channel error", cause);
            ctx.close();
        }
    }

    private final class JsonCodec extends MessageToMessageCodec<String, P2pMessage> {
        @Override
        protected void encode(ChannelHandlerContext ctx, P2pMessage msg, List<Object> out) throws Exception {
            out.add(MAPPER.writeValueAsString(msg));
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, String msg, List<Object> out) throws Exception {
            out.add(MAPPER.readValue(msg, P2pMessage.class));
        }
    }

    private static String remoteAddress(Channel channel) {
        InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
        String host = address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
        return host + ':' + address.getPort();
    }

    private final class PeerContext {
        final Channel channel;
        volatile String nodeId;
        volatile long lastSeen;
        volatile long lastPingSent;

        PeerContext(Channel channel) {
            this.channel = channel;
            this.lastSeen = System.currentTimeMillis();
            this.lastPingSent = 0L;
        }
    }

    private static final class LoggingPeerListener implements PeerListener {
        @Override
        public void onPeerConnected(Peer peer) {
            LOG.info(() -> "Peer connected: " + peer);
        }

        @Override
        public void onPeerDisconnected(Peer peer) {
            LOG.info(() -> "Peer disconnected: " + peer);
        }

        @Override
        public void onMessage(Peer peer, P2pMessage message) {
            LOG.fine(() -> "Received " + message.type() + " from " + peer.nodeId());
        }
    }
}
