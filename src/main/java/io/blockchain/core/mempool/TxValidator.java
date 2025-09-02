package io.blockchain.core.mempool;

import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.state.StateStore;

/** Stateful checks: balances, nonce ordering, fee floor. */
public final class TxValidator {

    private final StateStore state;
    private final long minFee;

    public TxValidator(StateStore state, long minFee) {
        this.state = state;
        this.minFee = minFee;
    }

    public void validate(Transaction tx) {
        tx.basicValidate(); // stateless checks first

        if (tx.feeMinor() < minFee) {
            throw new IllegalArgumentException("fee below minimum");
        }

        // Nonce must equal current expected nonce for sender
        long expected = state.getNonce(tx.from());
        if (tx.nonce() != expected) {
            throw new IllegalArgumentException("bad nonce: expected " + expected + " got " + tx.nonce());
        }

        // Sender must have enough balance for amount + fee
        long need = tx.amountMinor() + tx.feeMinor();
        long have = state.getBalance(tx.from());
        if (have < need) {
            throw new IllegalArgumentException("insufficient balance");
        }

        // Signature verification is TODO
    }
}
