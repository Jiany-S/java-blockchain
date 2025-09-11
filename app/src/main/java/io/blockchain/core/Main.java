package io.blockchain.core;

import java.util.function.Supplier;
import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.protocol.SignatureUtil;
import io.blockchain.core.metrics.BlockMetrics;
import io.blockchain.core.p2p.P2pServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.Optional;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        // Data dir from args[0] or default ./data/chain
        String dataDir = (args != null && args.length > 0) ? args[0] : "./data/chain";
        Files.createDirectories(Path.of(dataDir));

        Node node = Node.rocks(NodeConfig.defaultLocal(), dataDir);
        try {
            node.start();

            // --- Mining tick #1 ---
            long startHeight = currentHeight(node);
            BlockMetrics.recordMining(() -> node.tick());
            long afterTick = currentHeight(node);
            LOG.info("First tick: height " + startHeight + " → " + afterTick);

            // --- Generate key pair + sign tx ---
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            KeyPair kp = kpg.generateKeyPair();

            long nonce = node.state().getNonce("alice123456");
            Transaction tx = Transaction.builder()
                    .from("alice123456")
                    .to("bob654321")
                    .amountMinor(100)
                    .feeMinor(1)
                    .nonce(nonce)
                    .build();

            byte[] sig = SignatureUtil.sign(tx.serialize(), kp.getPrivate());
            tx = Transaction.builder()
                    .from(tx.from())
                    .to(tx.to())
                    .amountMinor(tx.amountMinor())
                    .feeMinor(tx.feeMinor())
                    .nonce(tx.nonce())
                    .signature(sig)
                    .build();

            if (SignatureUtil.verify(tx.serialize(), tx.signature(), kp.getPublic())) {
                node.mempool().add(tx);
                LOG.info("Signed tx added to mempool");
            } else {
                LOG.warning("Tx signature verification failed");
            }

            // --- Mine until tx included ---
            int tries = 5;
            for (int i = 0; i < tries; i++) {
                long before = currentHeight(node);
                Optional<byte[]> head = BlockMetrics.recordMining(() -> node.tick());
                long after = currentHeight(node);
                if (after > before) {
                    BlockMetrics.incrementBlocks();
                    LOG.info("Block mined at height " + after + " (try " + (i + 1) + ")");
                }
                if (head.isPresent()) break;
            }

            // --- Start P2P server ---
            P2pServer server = new P2pServer(9000);
            server.start();
            LOG.info("P2P server started on port 9000");

            // --- Print metrics ---
            LOG.info("=== Metrics ===");
            LOG.info(BlockMetrics.scrapeMetrics());

            // For demo: keep alive briefly
            Thread.sleep(5000);
            server.stop();
            } finally {
                node.close();
            }
    }

    private static long currentHeight(Node node) {
        return node.chain().getHead()
                .map(h -> node.chain().getHeight(h).orElse(-1L))
                .orElse(-1L);
    }
}
