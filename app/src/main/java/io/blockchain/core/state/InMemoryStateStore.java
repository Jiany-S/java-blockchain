package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryStateStore implements StateStore {

    private final Map<String, Long> balances = new HashMap<String, Long>();
    private final Map<String, Long> nonces   = new HashMap<String, Long>();

    @Override
    public synchronized long getBalance(String address) {
        Long v = balances.get(address);
        return v == null ? 0L : v;
    }

    @Override
    public synchronized long getNonce(String address) {
        Long v = nonces.get(address);
        return v == null ? 0L : v;
    }

    @Override
    public synchronized void applyTx(Transaction tx) {
        String from = tx.from();
        String to   = tx.to();
        long amt    = tx.amountMinor();
        long fee    = tx.feeMinor();

        long fromBal = getBalance(from);
        long fromNonce = getNonce(from);
        balances.put(from, fromBal - amt - fee);
        nonces.put(from, fromNonce + 1);

        long toBal = getBalance(to);
        balances.put(to, toBal + amt);
        // fee is burned for MVP
    }

    @Override
    public synchronized void revertTx(Transaction tx) {
        String from = tx.from();
        String to   = tx.to();
        long amt    = tx.amountMinor();
        long fee    = tx.feeMinor();

        long toBal = getBalance(to);
        balances.put(to, toBal - amt);

        long fromBal = getBalance(from);
        long fromNonce = getNonce(from);
        balances.put(from, fromBal + amt + fee);
        nonces.put(from, fromNonce - 1);
    }

    @Override
    public synchronized void applyBlock(Block block) {
        List<Transaction> txs = block.transactions();
        for (int i = 0; i < txs.size(); i++) applyTx(txs.get(i));
    }

    @Override
    public synchronized void revertBlock(Block block) {
        List<Transaction> txs = block.transactions();
        for (int i = txs.size() - 1; i >= 0; i--) revertTx(txs.get(i));
    }

    @Override
    public synchronized void credit(String address, long amount) {
        long bal = getBalance(address);
        balances.put(address, bal + amount);
    }
}
