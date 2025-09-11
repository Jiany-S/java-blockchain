package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory implementation of StateStore.
 * Tracks balances and nonces using simple HashMaps.
 * Not persistent — resets every process run.
 */
public final class InMemoryStateStore implements StateStore {

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Long> nonces   = new HashMap<>();

    @Override
    public synchronized long getBalance(String address) {
        return balances.getOrDefault(address, 0L);
    }

    @Override
    public synchronized long getNonce(String address) {
        return nonces.getOrDefault(address, 0L);
    }

    @Override
    public synchronized void setBalance(String address, long balance) {
        balances.put(address, balance);
    }

    @Override
    public synchronized void setNonce(String address, long nonce) {
        nonces.put(address, nonce);
    }

    @Override
    public synchronized void applyTransaction(Transaction tx) {
        long fromBal = getBalance(tx.from());
        if (fromBal < tx.amountMinor() + tx.feeMinor()) {
            throw new IllegalStateException("Insufficient balance during replay");
        }

        // debit sender
        balances.put(tx.from(), fromBal - tx.amountMinor() - tx.feeMinor());
        nonces.put(tx.from(), tx.nonce() + 1);

        // credit recipient
        balances.put(tx.to(), getBalance(tx.to()) + tx.amountMinor());

        // fee is burned for MVP (could later assign to miner)
    }

    @Override
    public synchronized void applyTx(Transaction tx) {
        applyTransaction(tx);
    }

    @Override
    public synchronized void revertTx(Transaction tx) {
        // reverse the applyTransaction effect
        balances.put(tx.to(), getBalance(tx.to()) - tx.amountMinor());
        balances.put(tx.from(), getBalance(tx.from()) + tx.amountMinor() + tx.feeMinor());
        nonces.put(tx.from(), getNonce(tx.from()) - 1);
    }

    @Override
    public synchronized void applyBlock(Block block) {
        for (Transaction tx : block.transactions()) {
            applyTransaction(tx);
        }
    }

    @Override
    public synchronized void revertBlock(Block block) {
        List<Transaction> txs = block.transactions();
        for (int i = txs.size() - 1; i >= 0; i--) {
            revertTx(txs.get(i));
        }
    }

    @Override
    public synchronized void credit(String address, long amount) {
        balances.put(address, getBalance(address) + amount);
    }
}
