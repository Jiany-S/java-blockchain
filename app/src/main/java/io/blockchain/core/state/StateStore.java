package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;

/**
 * Minimal account state: balances + nonces.
 */
public interface StateStore {
    long getBalance(String address);
    long getNonce(String address);
    void applyTransaction(Transaction tx);

    /** Apply a single transaction (checked by caller). */
    void applyTx(Transaction tx);

    /** Revert a single transaction (inverse of applyTx). */
    void revertTx(Transaction tx);

    /** Apply all txs in a block (in order). */
    void applyBlock(Block block);

    /** Revert all txs in a block (reverse order). */
    void revertBlock(Block block);

    /** Credit an address (genesis funding or manual recovery). */
    void credit(String address, long amount);
    void setBalance(String address, long balance);   // <— add this
    void setNonce(String address, long nonce);       // optional helper
    
}
