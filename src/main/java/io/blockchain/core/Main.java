package io.blockchain.core;

import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        // Data dir from args[0] or default ./data/chain
        String dataDir = args != null && args.length > 0 ? args[0] : "./data/chain";
        Files.createDirectories(Path.of(dataDir));

        Node node = Node.rocks(NodeConfig.defaultLocal(), dataDir);
        try {
            // If empty DB, this seeds genesis; otherwise picks up existing head
            node.start();

            long startHeight = node.chain().getHead()
                    .map(h -> node.chain().getHeight(h).orElse(-1L))
                    .orElse(-1L);
            LOG.info("Starting height: " + startHeight);

            // Mine one block (if mempool empty and height>0, PoW may still produce empty block if you want; fine for demo)
            Optional<byte[]> head = node.tick();

            long endHeight = node.chain().getHead()
                    .map(h -> node.chain().getHeight(h).orElse(-1L))
                    .orElse(-1L);

            LOG.info("Produced block? " + head.isPresent());
            LOG.info("Ending height: " + endHeight);
            LOG.info("Data dir: " + dataDir);
            LOG.info("Restart this app and you should see the same or higher height.");
        } finally {
            node.close(); // flush & close RocksDB
        }
    }
}
