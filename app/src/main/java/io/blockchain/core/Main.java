package io.blockchain.core;

import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.protocol.Transaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        // Data dir from args[0] or default ./data/chain
        String dataDir = (args != null && args.length > 0) ? args[0] : "./data/chain";
        Files.createDirectories(Path.of(dataDir));

        Node node = Node.rocks(NodeConfig.defaultLocal(), dataDir);
        try {
            // Seed balances each start (state is in-memory) and ensure genesis exists
            node.start();

            long startHeight = currentHeight(node);
            long t0 = System.currentTimeMillis();
            node.tick(); // try to produce a block (may be empty)
            long ms = System.currentTimeMillis() - t0;

            long afterFirstTick = currentHeight(node);
            boolean producedFirst = afterFirstTick > startHeight;
            LOG.info("Produced block? " + producedFirst + " (+" + (afterFirstTick - startHeight) + ") in " + ms + " ms");
            LOG.info("Height now: " + afterFirstTick);

            // Enqueue one demo tx (alice → bob), then try a few more ticks
            long nonce = node.state().getNonce("alice123456");
            Transaction tx = Transaction.builder()
                    .from("alice123456")
                    .to("bob654321")
                    .amountMinor(100)    // <= funded balance from NodeConfig.defaultLocal()
                    .feeMinor(1)
                    .nonce(nonce)
                    .build();
            node.mempool().add(tx);

            int tries = 5;
            int produced = 0;
            for (int i = 0; i < tries; i++) {
                long hBefore = currentHeight(node);
                node.tick();
                long hAfter = currentHeight(node);
                if (hAfter > hBefore) produced++;
            }

            long finalHeight = currentHeight(node);
            LOG.info("Mined " + produced + " block(s) in " + tries + " additional tick(s).");
            LOG.info("Ending height: " + finalHeight);
            LOG.info("Data dir: " + dataDir);
            LOG.info("Restart this app and you should see the same or higher height.");
        } finally {
            node.close(); // flush & close RocksDB
        }
    }

    private static long currentHeight(Node node) {
        return node.chain().getHead()
                .map(h -> node.chain().getHeight(h).orElse(-1L))
                .orElse(-1L);
    }
}
