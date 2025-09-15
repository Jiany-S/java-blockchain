package io.blockchain.core;

import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.protocol.SignatureUtil;
import io.blockchain.core.metrics.BlockMetrics;
import io.blockchain.core.p2p.P2pServer;
import io.blockchain.core.wallet.Wallet;
import io.blockchain.core.wallet.WalletStore;

import java.nio.file.Files;
import java.nio.file.Path;
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
            BlockMetrics.recordMining(node::tick);
            long afterTick = currentHeight(node);
            LOG.info("First tick: height " + startHeight + " → " + afterTick);

            // --- Create wallets ---
            WalletStore store = new WalletStore();
            Wallet alice = store.createWallet("alice");
            Wallet bob   = store.createWallet("bob");
            LOG.info("Alice addr=" + alice.getAddress());
            LOG.info("Bob   addr=" + bob.getAddress());

            // Credit Alice (genesis funds)
            node.state().credit(alice.getAddress(), 1_000_000);

            // --- Build unsigned tx ---
            long nonce = node.state().getNonce(alice.getAddress());
            Transaction unsignedTx = Transaction.builder()
                    .from(alice.getAddress())
                    .to(bob.getAddress())
                    .amountMinor(250)
                    .feeMinor(1)
                    .nonce(nonce)
                    .build();

            // --- Sign tx ---
            byte[] sig = alice.sign(unsignedTx.toUnsignedBytes());
            Transaction signedTx = Transaction.builder()
                    .from(unsignedTx.from())
                    .to(unsignedTx.to())
                    .amountMinor(unsignedTx.amountMinor())
                    .feeMinor(unsignedTx.feeMinor())
                    .nonce(unsignedTx.nonce())
                    .timestamp(unsignedTx.timestamp())  
                    .payload(unsignedTx.payload())      
                    .signature(sig)
                    .build();
            // --- Verify signature using Alice's public key ---
            if (Wallet.verifyWithKey(
                    signedTx.toUnsignedBytes(),
                    signedTx.signature(),
                    alice.getPublicKey()
            )) {
                node.mempool().add(signedTx);
                LOG.info("✔ Tx signed and added to mempool");
            } else {
                LOG.warning("✖ Tx signature verification failed");
            }


            // --- Mine until tx included ---
            for (int i = 0; i < 5; i++) {
                long before = currentHeight(node);
                Optional<byte[]> head = BlockMetrics.recordMining(node::tick);
                long after = currentHeight(node);
                if (after > before) {
                    BlockMetrics.incrementBlocks();
                    LOG.info("Block mined at height " + after + " (try " + (i + 1) + ")");
                }
                if (head.isPresent()) break;
            }

            // --- Show balances ---
            LOG.info("Alice balance=" + node.state().getBalance(alice.getAddress()));
            LOG.info("Bob   balance=" + node.state().getBalance(bob.getAddress()));

            // --- Start P2P server ---
            P2pServer server = new P2pServer(9000);
            server.start();
            LOG.info("P2P server started on port 9000");

            // --- Print metrics ---
            LOG.info("=== Metrics ===");
            LOG.info(BlockMetrics.scrapeMetrics());

            // Keep alive briefly
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
