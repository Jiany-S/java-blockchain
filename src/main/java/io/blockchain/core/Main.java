package io.blockchain.core;

import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.rpc.RpcServer;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Boots the node, starts HTTP, mines a few blocks. */
public class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        // Make local PoW easy
        Node node = Node.inMemory(NodeConfig.defaultLocal());
        node.start();

        // Start tiny HTTP server (below)
        RpcServer http = new RpcServer(node, 8080);
        http.start();
        LOG.info("HTTP listening on http://localhost:8080");

        // Mine a handful of blocks so you see progress
        for (int i = 0; i < 5; i++) {
            long t0 = System.currentTimeMillis();
            Optional<byte[]> head = node.tick();
            long ms = System.currentTimeMillis() - t0;
            if (head.isPresent()) {
                LOG.info("New block mined in " + ms + " ms; height="
                        + node.chain().getHeight(head.get()).orElse(-1L));
            } else {
                LOG.info("No block produced this tick (try again)"); // e.g., no txs & height>0, or PoW not found
            }
        }

        // keep process alive for HTTP (Ctrl+C to stop)
        Thread.currentThread().join();
    }
}
