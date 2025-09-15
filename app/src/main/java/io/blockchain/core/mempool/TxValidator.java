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

    public boolean validate(Transaction tx) {
        if (state == null) return true;
        if (tx.feeMinor() < minFee) return false;
        long bal = state.getBalance(tx.from());
        return bal >= (tx.amountMinor() + tx.feeMinor());
    }
}
