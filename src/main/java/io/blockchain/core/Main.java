package io.blockchain.core;

import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.protocol.Transaction;

import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        Node node = Node.inMemory(NodeConfig.defaultLocal());
        node.start();

        // Submit a real tx (alice -> bob)
        Transaction tx = Transaction.builder()
                .from("alice123456")
                .to("bob654321")
                .amountMinor(100)   // now funded by genesis
                .feeMinor(1)
                .nonce(0)           // alice's first tx
                .build();
        node.mempool().add(tx);

        Optional<byte[]> head = node.tick();
        System.out.println("Produced block? " + head.isPresent());
        System.out.println("alice balance = " + node.state().getBalance("alice123456"));
        System.out.println("bob balance   = " + node.state().getBalance("bob654321"));
    }
}
