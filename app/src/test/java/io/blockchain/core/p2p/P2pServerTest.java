package io.blockchain.core.p2p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class P2pServerTest {

    private final List<P2pServer> servers = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (P2pServer server : servers) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        servers.clear();
    }

    @Test
    void periodicMessagesAreBroadcast() throws Exception {
        int portA = freePort();
        int portB = freePort();

        CountDownLatch handshake = new CountDownLatch(2);
        CountDownLatch messageLatch = new CountDownLatch(1);
        RecordingListener listenerA = new RecordingListener(new CopyOnWriteArrayList<>(), handshake);
        PeriodicRecorder listenerB = new PeriodicRecorder(handshake, messageLatch);

        P2pServer serverA = createServer("node-A", portA, listenerA, 200L, 1_000L, true);
        P2pServer serverB = createServer("node-B", portB, listenerB, 200L, 1_000L, true);
        serverA.registerPeriodicMessage(new P2pMessage("announce", Map.of("height", 42)));

        serverA.start();
        serverB.start();
        serverB.connect("127.0.0.1", portA);

        assertTrue(handshake.await(5, TimeUnit.SECONDS));
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void periodicSuppliersEmitFreshPayload() throws Exception {
        int portA = freePort();
        int portB = freePort();

        AtomicInteger counter = new AtomicInteger();
        CountDownLatch handshake = new CountDownLatch(2);
        CountDownLatch messageLatch = new CountDownLatch(1);
        RecordingListener listenerA = new RecordingListener(new CopyOnWriteArrayList<>(), handshake);
        SupplierRecorder listenerB = new SupplierRecorder(handshake, messageLatch);

        P2pServer serverA = createServer("node-A", portA, listenerA, 200L, 1_000L, true);
        P2pServer serverB = createServer("node-B", portB, listenerB, 200L, 1_000L, true);
        serverA.registerPeriodicSupplier(() -> new P2pMessage("announce", Map.of("height", counter.incrementAndGet())));

        serverA.start();
        serverB.start();
        serverB.connect("127.0.0.1", portA);

        assertTrue(handshake.await(5, TimeUnit.SECONDS));
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        assertTrue(listenerB.lastHeight() > 0);
    }

    @Test
    void handshakeExchangesNodeIds() throws Exception {
        int portA = freePort();
        int portB = freePort();

        CountDownLatch latch = new CountDownLatch(2);
        CopyOnWriteArrayList<String> seenByA = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> seenByB = new CopyOnWriteArrayList<>();

        P2pServer serverA = createServer("node-A", portA, new RecordingListener(seenByA, latch));
        P2pServer serverB = createServer("node-B", portB, new RecordingListener(seenByB, latch));

        serverA.start();
        serverB.start();

        serverB.connect("127.0.0.1", portA);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Peers should handshake in time");
        assertTrue(seenByA.contains("node-B"));
        assertTrue(seenByB.contains("node-A"));

        assertEquals(1, serverA.peers().size());
        P2pServer.Peer peerFromA = serverA.peers().iterator().next();
        assertEquals("node-B", peerFromA.nodeId());
    }

    @Test
    void heartbeatSendsPingMessages() throws Exception {
        int portA = freePort();
        int portB = freePort();

        CountDownLatch handshake = new CountDownLatch(2);
        CountDownLatch pingLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> seenByA = new CopyOnWriteArrayList<>();

        RecordingListener listenerA = new RecordingListener(seenByA, handshake);
        HeartbeatRecorder listenerB = new HeartbeatRecorder(handshake, pingLatch);

        P2pServer serverA = createServer("node-A", portA, listenerA, 200L, 1_000L, true);
        P2pServer serverB = createServer("node-B", portB, listenerB, 200L, 1_000L, true);

        serverA.start();
        serverB.start();

        serverB.connect("127.0.0.1", portA);

        assertTrue(handshake.await(5, TimeUnit.SECONDS));
        assertTrue(pingLatch.await(5, TimeUnit.SECONDS));
        assertTrue(listenerB.messages.stream().anyMatch(msg -> "ping".equals(msg.type())));
    }

    @Test
    void stalePeerIsDisconnectedWhenNoHeartbeat() throws Exception {
        int portA = freePort();
        int portB = freePort();

        CountDownLatch handshake = new CountDownLatch(2);
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        DisconnectRecorder listenerA = new DisconnectRecorder(handshake, disconnectLatch);
        PassiveListener listenerB = new PassiveListener(handshake);

        P2pServer serverA = createServer("node-A", portA, listenerA, 200L, 600L, true);
        P2pServer serverB = createServer("node-B", portB, listenerB, 50_000L, 600L, false);

        serverA.start();
        serverB.start();

        serverB.connect("127.0.0.1", portA);

        assertTrue(handshake.await(5, TimeUnit.SECONDS));
        assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverA.peers().isEmpty());
    }

    private P2pServer createServer(String nodeId, int port, P2pServer.PeerListener listener) {
        return createServer(nodeId, port, listener, 10_000L, 30_000L, true);
    }

    private P2pServer createServer(String nodeId, int port, P2pServer.PeerListener listener, long pingIntervalMillis, long idleTimeoutMillis, boolean autoRespondPings) {
        P2pServer server = new P2pServer(nodeId, port, listener, pingIntervalMillis, idleTimeoutMillis, autoRespondPings);
        servers.add(server);
        return server;
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static final class PeriodicRecorder implements P2pServer.PeerListener {
        private final CountDownLatch handshakeLatch;
        private final CountDownLatch messageLatch;

        PeriodicRecorder(CountDownLatch handshakeLatch, CountDownLatch messageLatch) {
            this.handshakeLatch = handshakeLatch;
            this.messageLatch = messageLatch;
        }

        @Override
        public void onPeerConnected(P2pServer.Peer peer) {
            handshakeLatch.countDown();
        }

        @Override
        public void onPeerDisconnected(P2pServer.Peer peer) {
        }

        @Override
        public void onMessage(P2pServer.Peer peer, P2pMessage message) {
            if ("announce".equals(message.type())) {
                messageLatch.countDown();
            }
        }
    }

    private static final class SupplierRecorder implements P2pServer.PeerListener {
        private final CountDownLatch handshakeLatch;
        private final CountDownLatch messageLatch;
        private final AtomicInteger height = new AtomicInteger();

        SupplierRecorder(CountDownLatch handshakeLatch, CountDownLatch messageLatch) {
            this.handshakeLatch = handshakeLatch;
            this.messageLatch = messageLatch;
        }

        @Override
        public void onPeerConnected(P2pServer.Peer peer) {
            handshakeLatch.countDown();
        }

        @Override
        public void onPeerDisconnected(P2pServer.Peer peer) {
        }

        @Override
        public void onMessage(P2pServer.Peer peer, P2pMessage message) {
            if ("announce".equals(message.type())) {
                Object h = message.payload().get("height");
                if (h instanceof Number number) {
                    height.set(number.intValue());
                }
                messageLatch.countDown();
            }
        }

        int lastHeight() {
            return height.get();
        }
    }

    private static final class RecordingListener implements P2pServer.PeerListener {
        private final List<String> peers;
        private final CountDownLatch latch;

        RecordingListener(List<String> peers, CountDownLatch latch) {
            this.peers = peers;
            this.latch = latch;
        }

        @Override
        public void onPeerConnected(P2pServer.Peer peer) {
            peers.add(peer.nodeId());
            latch.countDown();
        }

        @Override
        public void onPeerDisconnected(P2pServer.Peer peer) {
        }

        @Override
        public void onMessage(P2pServer.Peer peer, P2pMessage message) {
        }
    }

    private static final class HeartbeatRecorder implements P2pServer.PeerListener {
        private final CountDownLatch handshakeLatch;
        private final CountDownLatch pingLatch;
        private final CopyOnWriteArrayList<P2pMessage> messages = new CopyOnWriteArrayList<>();

        HeartbeatRecorder(CountDownLatch handshakeLatch, CountDownLatch pingLatch) {
            this.handshakeLatch = handshakeLatch;
            this.pingLatch = pingLatch;
        }

        @Override
        public void onPeerConnected(P2pServer.Peer peer) {
            handshakeLatch.countDown();
        }

        @Override
        public void onPeerDisconnected(P2pServer.Peer peer) {
        }

        @Override
        public void onMessage(P2pServer.Peer peer, P2pMessage message) {
            messages.add(message);
            if ("ping".equals(message.type())) {
                pingLatch.countDown();
            }
        }
    }

    private static final class DisconnectRecorder implements P2pServer.PeerListener {
        private final CountDownLatch handshakeLatch;
        private final CountDownLatch disconnectLatch;

        DisconnectRecorder(CountDownLatch handshakeLatch, CountDownLatch disconnectLatch) {
            this.handshakeLatch = handshakeLatch;
            this.disconnectLatch = disconnectLatch;
        }

        @Override
        public void onPeerConnected(P2pServer.Peer peer) {
            handshakeLatch.countDown();
        }

        @Override
        public void onPeerDisconnected(P2pServer.Peer peer) {
            disconnectLatch.countDown();
        }

        @Override
        public void onMessage(P2pServer.Peer peer, P2pMessage message) {
        }
    }

    private static final class PassiveListener implements P2pServer.PeerListener {
        private final CountDownLatch handshakeLatch;

        PassiveListener(CountDownLatch handshakeLatch) {
            this.handshakeLatch = handshakeLatch;
        }

        @Override
        public void onPeerConnected(P2pServer.Peer peer) {
            handshakeLatch.countDown();
        }

        @Override
        public void onPeerDisconnected(P2pServer.Peer peer) {
        }

        @Override
        public void onMessage(P2pServer.Peer peer, P2pMessage message) {
        }
    }

}
