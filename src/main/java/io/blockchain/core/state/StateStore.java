package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;

/**
 * Minimal account state: balances + nonces.
 * Apply/revert blocks so fork-choice can switch heads later.
 */
public interface StateStore {
    long getBalance(String address);
    long getNonce(String address);

    /** Apply a single transaction (checked by caller). */
    void applyTx(Transaction tx);

    /** Revert a single transaction (inverse of applyTx). */
    void revertTx(Transaction tx);

    /** Apply all txs in a block (in order). */
    void applyBlock(Block block);

    /** Revert all txs in a block (reverse order). */
    void revertBlock(Block block);
}
