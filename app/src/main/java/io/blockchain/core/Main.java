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
import io.blockchain.core.wallet.WalletStore.WalletInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        Path dataPath = options.dataDir.toAbsolutePath().normalize();

        if (options.resetChain) {
            resetChainState(dataPath);
        }

        Files.createDirectories(dataPath);
        Path walletsPath = dataPath.resolve("wallets");
        WalletStore store = new WalletStore(walletsPath);
        Wallet alice = ensureWallet(store, "alice");
        Wallet bob = ensureWallet(store, "bob");

        Path allocFile = dataPath.resolve("genesis-alloc.json");
        Map<String, Long> allocations = loadAllocations(allocFile);
        if (options.regenGenesis) {
            allocations = regenerateAllocationsTemplate(store, allocFile);
        }

        NodeConfig config = NodeConfig.defaultLocal();
        Node node = Node.rocks(config, dataPath.toString());

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
            LOG.info("First tick: height " + startHeight + " -> " + afterTick);

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
                LOG.info("Tx signed and added to mempool");
            } else {
                LOG.warning("Tx signature verification failed");
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

    private static Map<String, Long> regenerateAllocationsTemplate(WalletStore store, Path allocFile) {
        Map<String, Long> template = new LinkedHashMap<>();
        for (WalletInfo info : store.listWallets()) {
            template.put(info.address, 0L);
        }
        saveAllocations(allocFile, template);
        LOG.info("Regenerated genesis allocations at " + allocFile + ". Update amounts as needed.");
        return template;
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
            Files.createDirectories(path.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), allocations);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist genesis allocations to " + path, e);
        }
    }

    private static void resetChainState(Path dataPath) {
        if (!Files.exists(dataPath)) {
            return;
        }
        Path walletsDir = dataPath.resolve("wallets").normalize();
        try (Stream<Path> stream = Files.walk(dataPath)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(dataPath))
                    .filter(path -> walletsDir == null || !path.normalize().startsWith(walletsDir))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to delete " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reset chain data in " + dataPath, e);
        }
        LOG.info("Cleared chain data under " + dataPath + " (wallets preserved).");
    }

    private static long currentHeight(Node node) {
        return node.chain().getHead()
                .map(h -> node.chain().getHeight(h).orElse(-1L))
                .orElse(-1L);
    }

    private record CliOptions(Path dataDir, boolean resetChain, boolean regenGenesis) {
        static CliOptions parse(String[] args) {
            Path dataDir = Path.of("./data/chain");
            boolean reset = false;
            boolean regen = false;
            if (args != null) {
                for (String arg : args) {
                    if (arg == null || arg.isBlank()) {
                        continue;
                    }
                    if (arg.startsWith("--data-dir=")) {
                        dataDir = Path.of(arg.substring("--data-dir=".length()));
                    } else if (arg.equals("--reset-chain")) {
                        reset = true;
                    } else if (arg.equals("--regen-genesis")) {
                        regen = true;
                    } else if (!arg.startsWith("--")) {
                        dataDir = Path.of(arg);
                    }
                }
            }
            return new CliOptions(dataDir, reset, regen);
        }
    }
}



