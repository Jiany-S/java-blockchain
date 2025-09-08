package io.blockchain.core.mempool;

import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.state.StateStore;

/** Stateful checks: nonce ordering, balance >= amount+fee, min fee. */
public final class TxValidator {

    private final StateStore state;
    private final long minFee;

    public TxValidator(StateStore state, long minFee) {
        this.state = state;
        this.minFee = minFee;
    }

    public void validate(Transaction tx) {
        // Stateless validity first
        tx.basicValidate();

        if (tx.feeMinor() < minFee) {
            throw new IllegalArgumentException("fee below minimum");
        }

        long expected = state.getNonce(tx.from());
        if (tx.nonce() != expected) {
            throw new IllegalArgumentException("bad nonce: expected " + expected + " got " + tx.nonce());
        }

        long need = tx.amountMinor() + tx.feeMinor();
        long have = state.getBalance(tx.from());
        if (have < need) {
            throw new IllegalArgumentException("insufficient balance");
        }
        // TODO: signature verification later
    }
}
