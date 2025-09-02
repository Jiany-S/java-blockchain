package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple in-memory state.
 * - Balance and nonce default to 0 if unseen.
 * - Uses long "minor units"; watch for overflow in real code.
 */
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

        // debit sender: amount + fee; bump nonce
        long fromBal = getBalance(from);
        long fromNonce = getNonce(from);
        balances.put(from, fromBal - amt - fee);
        nonces.put(from, fromNonce + 1);

        // credit receiver: amount
        long toBal = getBalance(to);
        balances.put(to, toBal + amt);
        // fees are "burned" for MVP; later send to miner
    }

    @Override
    public synchronized void revertTx(Transaction tx) {
        String from = tx.from();
        String to   = tx.to();
        long amt    = tx.amountMinor();
        long fee    = tx.feeMinor();

        // undo credits/debits
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
        for (int i = 0; i < txs.size(); i++) {
            applyTx(txs.get(i));
        }
    }

    @Override
    public synchronized void revertBlock(Block block) {
        List<Transaction> txs = block.transactions();
        for (int i = txs.size() - 1; i >= 0; i--) {
            revertTx(txs.get(i));
        }
    }
}
