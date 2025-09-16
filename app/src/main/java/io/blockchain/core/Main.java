package io.blockchain.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blockchain.core.metrics.BlockMetrics;
import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.p2p.P2pServer;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.wallet.Wallet;
import io.blockchain.core.wallet.WalletStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String dataDir = (args != null && args.length > 0) ? args[0] : "./data/chain";
        Path dataPath = Path.of(dataDir);
        Files.createDirectories(dataPath);

        WalletStore store = new WalletStore(dataPath.resolve("wallets"));
        Wallet alice = ensureWallet(store, "alice");
        Wallet bob = ensureWallet(store, "bob");

        NodeConfig config = NodeConfig.defaultLocal();
        Node node = Node.rocks(config, dataDir);

        Path allocFile = dataPath.resolve("genesis-alloc.json");
        Map<String, Long> allocations = loadAllocations(allocFile);
        boolean chainIsEmpty = node.chain().getHead().isEmpty();
        if (allocations == null && chainIsEmpty) {
            allocations = new LinkedHashMap<>();
            allocations.put(alice.getAddress(), 1_000_000L);
            allocations.put(bob.getAddress(), 500_000L);
            saveAllocations(allocFile, allocations);
        }
        if (allocations != null) {
            config.genesisAllocations.clear();
            config.genesisAllocations.putAll(allocations);
        }

        try {
            node.start();

            LOG.info("Alice addr=" + alice.getAddress());
            LOG.info("Bob   addr=" + bob.getAddress());

            long startHeight = currentHeight(node);
            BlockMetrics.recordMining(node::tick);
            long afterTick = currentHeight(node);
            LOG.info("First tick: height " + startHeight + " ? " + afterTick);

            long nonce = node.state().getNonce(alice.getAddress());
            Transaction unsignedTx = Transaction.builder()
                    .from(alice.getAddress())
                    .to(bob.getAddress())
                    .amountMinor(250)
                    .feeMinor(1)
                    .nonce(nonce)
                    .build();

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
                    .publicKey(alice.getPublicKey())
                    .build();

            if (Wallet.verifyWithKey(
                    signedTx.toUnsignedBytes(),
                    signedTx.signature(),
                    alice.getPublicKey()
            )) {
                node.mempool().add(signedTx);
                LOG.info("? Tx signed and added to mempool");
            } else {
                LOG.warning("? Tx signature verification failed");
            }

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

            LOG.info("Alice balance=" + node.state().getBalance(alice.getAddress()));
            LOG.info("Bob   balance=" + node.state().getBalance(bob.getAddress()));

            P2pServer server = new P2pServer(9000);
            server.start();
            LOG.info("P2P server started on port 9000");

            LOG.info("=== Metrics ===");
            LOG.info(BlockMetrics.scrapeMetrics());

            Thread.sleep(5000);
            server.stop();
        } finally {
            node.close();
        }
    }

    private static Wallet ensureWallet(WalletStore store, String alias) throws Exception {
        Wallet wallet = store.getWallet(alias);
        if (wallet != null) {
            return wallet;
        }
        return store.createWallet(alias);
    }

    private static Map<String, Long> loadAllocations(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return JSON.readValue(path.toFile(), new TypeReference<Map<String, Long>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read genesis allocations from " + path, e);
        }
    }

    private static void saveAllocations(Path path, Map<String, Long> allocations) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), allocations);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist genesis allocations to " + path, e);
        }
    }

    private static long currentHeight(Node node) {
        return node.chain().getHead()
                .map(h -> node.chain().getHeight(h).orElse(-1L))
                .orElse(-1L);
    }
}
