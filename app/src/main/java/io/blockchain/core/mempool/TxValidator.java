package io.blockchain.core.mempool;

import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.state.StateStore;

public class TxValidator {
    private final StateStore state;
    private final long minFee;

    public TxValidator(StateStore state, long minFee) {
        this.state = state;
        this.minFee = minFee;
    }

    public TxValidator() {
        this.state = null;
        this.minFee = 0;
    }

    public void validate(Transaction tx) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction required");
        }
        if (state == null) {
            return;
        }
        if (tx.feeMinor() < minFee) {
            throw new IllegalArgumentException("Fee below minimum");
        }
        long required = tx.amountMinor() + tx.feeMinor();
        long balance = state.getBalance(tx.from());
        if (balance < required) {
            throw new IllegalArgumentException("Insufficient balance");
        }
        long currentNonce = state.getNonce(tx.from());
        if (tx.nonce() < currentNonce) {
            throw new IllegalArgumentException("Nonce too low");
        }
    }
}
