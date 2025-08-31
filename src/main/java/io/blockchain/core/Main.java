package io.blockchain.core;

import io.blockchain.core.protocol.Transaction;

public class Main {
    public static void main(String[] args) {
        System.out.println("Blockchain node starting... ✅");

        // quick smoke-test: build a dummy transaction
        Transaction tx = Transaction.builder()
                .from("alice123456")
                .to("bob654321")
                .amountMinor(1000)
                .feeMinor(10)
                .nonce(1)
                .build();

        System.out.println("Created transaction: " + tx);
        System.out.println("Tx ID: " + tx.idHex());
    }
}
