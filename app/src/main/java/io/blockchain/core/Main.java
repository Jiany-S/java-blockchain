package io.blockchain.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blockchain.core.api.ApiServer;
import io.blockchain.core.metrics.BlockMetrics;
import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.p2p.P2pServer;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.rpc.RpcServer;
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
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        if (options.showHelp) {
            options.printHelp();
            if (options.errorMessage != null) {
                System.exit(1);
            }
            return;
        }

        Path dataPath = options.dataDir().toAbsolutePath().normalize();
        if (options.resetChain()) {
            resetChainState(dataPath);
        }
        Files.createDirectories(dataPath);

        Path walletsPath = dataPath.resolve("wallets");
        WalletStore store = new WalletStore(walletsPath);
        Wallet alice = ensureWallet(store, "alice");
        Wallet bob = ensureWallet(store, "bob");

        Path allocFile = dataPath.resolve("genesis-alloc.json");
        Map<String, Long> allocations = loadAllocations(allocFile);
        if (options.regenGenesis()) {
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

        ApiServer apiServer = null;
        RpcServer rpcServer = null;
        P2pServer p2pServer = null;
        CountDownLatch shutdownLatch = null;

        try {
            node.start();
            LOG.info("Alice addr=" + alice.getAddress());
            LOG.info("Bob   addr=" + bob.getAddress());

            if (options.demo()) {
                runDemoFlow(node, alice, bob);
            } else {
                LOG.info("Demo flow disabled (--no-demo)");
            }

            if (options.enableP2p()) {
                p2pServer = new P2pServer(options.p2pPort());
                p2pServer.start();
            }

            if (options.enableApi()) {
                apiServer = new ApiServer(
                        node,
                        store,
                        options.apiBind(),
                        options.apiPort(),
                        options.apiToken()
                );
                apiServer.start();
            }

            if (options.enableRpc()) {
                rpcServer = new RpcServer(
                        node,
                        store,
                        options.rpcBind(),
                        options.rpcPort(),
                        options.rpcToken()
                );
                rpcServer.start();
            }

            boolean keepAlive = options.keepAlive() || options.enableApi() || options.enableRpc();
            if (keepAlive) {
                shutdownLatch = new CountDownLatch(1);
                CountDownLatch finalLatch = shutdownLatch;
                Runtime.getRuntime().addShutdownHook(new Thread(finalLatch::countDown, "java-blockchain-shutdown"));
            }

            if (keepAlive) {
                LOG.info("Node running. Press CTRL+C to exit.");
                shutdownLatch.await();
            } else if (options.demoDurationMillis() > 0) {
                try {
                    Thread.sleep(options.demoDurationMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            if (apiServer != null) {
                apiServer.stop();
            }
            if (rpcServer != null) {
                rpcServer.stop();
            }
            if (p2pServer != null) {
                p2pServer.stop();
            }
            node.close();
        }
    }

    private static void runDemoFlow(Node node, Wallet alice, Wallet bob) {
        node.state().credit(alice.getAddress(), 1_000_000);

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
                alice.getPublicKey())) {
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
            if (head.isPresent()) {
                break;
            }
        }

        LOG.info("Alice balance=" + node.state().getBalance(alice.getAddress()));
        LOG.info("Bob   balance=" + node.state().getBalance(bob.getAddress()));
        LOG.info("=== Metrics ===\n" + BlockMetrics.scrapeMetrics());
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

    private record CliOptions(
            boolean showHelp,
            String errorMessage,
            Path dataDir,
            boolean resetChain,
            boolean regenGenesis,
            boolean keepAlive,
            boolean demo,
            long demoDurationMillis,
            boolean enableApi,
            String apiBind,
            int apiPort,
            String apiToken,
            boolean enableRpc,
            String rpcBind,
            int rpcPort,
            String rpcToken,
            boolean enableP2p,
            int p2pPort
    ) {
        static CliOptions parse(String[] args) {
            Path dataDir = envPath("JAVA_CHAIN_DATA_DIR", Path.of("./data/chain"));
            boolean reset = false;
            boolean regen = false;
            boolean keepAlive = false;
            boolean demo = true;
            long demoDurationMillis = 5_000L;
            boolean enableApi = "true".equalsIgnoreCase(System.getenv("JAVA_CHAIN_ENABLE_API"));
            String apiBind = envOrDefault("JAVA_CHAIN_API_BIND", "127.0.0.1");
            int apiPort = envPort("JAVA_CHAIN_API_PORT", 8080);
            String apiToken = System.getenv("JAVA_CHAIN_API_TOKEN");
            boolean enableRpc = "true".equalsIgnoreCase(System.getenv("JAVA_CHAIN_ENABLE_RPC"));
            String rpcBind = envOrDefault("JAVA_CHAIN_RPC_BIND", "127.0.0.1");
            int rpcPort = envPort("JAVA_CHAIN_RPC_PORT", 9090);
            String rpcToken = System.getenv("JAVA_CHAIN_RPC_TOKEN");
            boolean enableP2p = !"false".equalsIgnoreCase(System.getenv("JAVA_CHAIN_ENABLE_P2P"));
            int p2pPort = envPort("JAVA_CHAIN_P2P_PORT", 9000);
            boolean showHelp = false;
            String error = null;

            if (args != null) {
                for (String arg : args) {
                    if (arg == null || arg.isBlank()) {
                        continue;
                    }
                    if ("--help".equals(arg) || "-h".equals(arg)) {
                        showHelp = true;
                    } else if (arg.startsWith("--data-dir=")) {
                        dataDir = Path.of(arg.substring("--data-dir=".length()));
                    } else if (arg.equals("--reset-chain")) {
                        reset = true;
                    } else if (arg.equals("--regen-genesis")) {
                        regen = true;
                    } else if (arg.equals("--keep-alive")) {
                        keepAlive = true;
                    } else if (arg.equals("--demo")) {
                        demo = true;
                    } else if (arg.equals("--no-demo")) {
                        demo = false;
                    } else if (arg.startsWith("--demo-duration-ms=")) {
                        try {
                            demoDurationMillis = parsePositiveLong(arg.substring("--demo-duration-ms=".length()), "--demo-duration-ms");
                        } catch (IllegalArgumentException ex) {
                            showHelp = true;
                            error = ex.getMessage();
                        }
                    } else if (arg.equals("--enable-api")) {
                        enableApi = true;
                    } else if (arg.startsWith("--api-bind=")) {
                        apiBind = arg.substring("--api-bind=".length());
                    } else if (arg.startsWith("--api-port=")) {
                        try {
                            apiPort = parsePort(arg.substring("--api-port=".length()), "--api-port");
                        } catch (IllegalArgumentException ex) {
                            showHelp = true;
                            error = ex.getMessage();
                        }
                    } else if (arg.startsWith("--api-token=")) {
                        apiToken = arg.substring("--api-token=".length());
                    } else if (arg.equals("--enable-rpc")) {
                        enableRpc = true;
                    } else if (arg.startsWith("--rpc-bind=")) {
                        rpcBind = arg.substring("--rpc-bind=".length());
                    } else if (arg.startsWith("--rpc-port=")) {
                        try {
                            rpcPort = parsePort(arg.substring("--rpc-port=".length()), "--rpc-port");
                        } catch (IllegalArgumentException ex) {
                            showHelp = true;
                            error = ex.getMessage();
                        }
                    } else if (arg.startsWith("--rpc-token=")) {
                        rpcToken = arg.substring("--rpc-token=".length());
                    } else if (arg.equals("--no-p2p")) {
                        enableP2p = false;
                    } else if (arg.startsWith("--p2p-port=")) {
                        try {
                            p2pPort = parsePort(arg.substring("--p2p-port=".length()), "--p2p-port");
                        } catch (IllegalArgumentException ex) {
                            showHelp = true;
                            error = ex.getMessage();
                        }
                    } else if (!arg.startsWith("--")) {
                        dataDir = Path.of(arg);
                    } else if (error == null) {
                        showHelp = true;
                        error = "Unknown option: " + arg;
                    }
                }
            }

            if (apiToken == null || apiToken.isBlank()) {
                apiToken = System.getenv("JAVA_CHAIN_API_TOKEN");
            }
            if (rpcToken == null || rpcToken.isBlank()) {
                rpcToken = System.getenv("JAVA_CHAIN_RPC_TOKEN");
            }

            keepAlive = keepAlive || enableApi || enableRpc || "true".equalsIgnoreCase(System.getenv("JAVA_CHAIN_KEEP_ALIVE"));

            return new CliOptions(
                    showHelp,
                    error,
                    dataDir,
                    reset,
                    regen,
                    keepAlive,
                    demo,
                    demoDurationMillis,
                    enableApi,
                    apiBind,
                    apiPort,
                    apiToken,
                    enableRpc,
                    rpcBind,
                    rpcPort,
                    rpcToken,
                    enableP2p,
                    p2pPort
            );
        }

        void printHelp() {
            if (errorMessage != null) {
                System.err.println("Error: " + errorMessage);
            }
            System.out.println("""
Usage: java-blockchain [options]

Options:
  --help, -h                 Show this help message and exit
  --data-dir=<path>          Path for blockchain data (default ./data/chain)
  --reset-chain              Delete chain data (wallets are preserved)
  --regen-genesis            Regenerate genesis-alloc.json from known wallets
  --keep-alive               Keep the node running until interrupted
  --demo / --no-demo         Enable (default) or disable the demo transaction flow
  --demo-duration-ms=<ms>    How long to keep the JVM alive when not using --keep-alive (default 5000)
  --enable-api               Start the REST API server (default bind 127.0.0.1:8080)
  --api-bind=<host>          Bind address for the REST API
  --api-port=<port>          Port for the REST API (default 8080)
  --api-token=<token>        Require Bearer/X-API-Key token for the REST API
  --enable-rpc               Start the RPC server (default bind 127.0.0.1:9090)
  --rpc-bind=<host>          Bind address for the RPC server
  --rpc-port=<port>          Port for the RPC server (default 9090)
  --rpc-token=<token>        Require Bearer/X-API-Key token for the RPC server
  --no-p2p                   Disable the Netty P2P listener
  --p2p-port=<port>          Port for the P2P listener (default 9000)

Environment overrides:
  JAVA_CHAIN_DATA_DIR        Override --data-dir
  JAVA_CHAIN_API_TOKEN       Token for REST API auth (if --api-token not supplied)
  JAVA_CHAIN_RPC_TOKEN       Token for RPC auth (if --rpc-token not supplied)
  JAVA_CHAIN_ENABLE_API      Set to "true" to enable REST API without CLI flag
  JAVA_CHAIN_ENABLE_RPC      Set to "true" to enable RPC without CLI flag
  JAVA_CHAIN_KEEP_ALIVE      Set to "true" to force keep-alive mode
""");
        }

        private static Path envPath(String key, Path fallback) {
            String value = System.getenv(key);
            return (value == null || value.isBlank()) ? fallback : Path.of(value);
        }

        private static String envOrDefault(String key, String fallback) {
            String value = System.getenv(key);
            return (value == null || value.isBlank()) ? fallback : value;
        }

        private static int envPort(String key, int fallback) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return parsePort(value, key);
        }

        private static int parsePort(String value, String flag) {
            try {
                int port = Integer.parseInt(value);
                if (port <= 0 || port > 65_535) {
                    throw new NumberFormatException();
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port for " + flag + ": " + value);
            }
        }

        private static long parsePositiveLong(String value, String flag) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid value for " + flag + ": " + value);
            }
        }
    }
}
