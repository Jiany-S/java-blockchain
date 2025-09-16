package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;

import java.util.Map;

public final class StateReplayer {
    private StateReplayer(){}

    public static void replay(ChainStore chain, StateStore state, Map<String, Long> allocations) {
        if (allocations != null) {
            for (Map.Entry<String, Long> entry : allocations.entrySet()) {
                String address = entry.getKey();
                if (address == null || address.isBlank()) {
                    continue;
                }
                long amount = entry.getValue() == null ? 0L : entry.getValue();
                state.setBalance(address, amount);
                state.setNonce(address, 0L);
            }
        }

        for (Block block : chain.getBlocksInOrder()) {
            for (Transaction tx : block.transactions()) {
                long required = tx.amountMinor() + tx.feeMinor();
                long current = state.getBalance(tx.from());
                if (current < required) {
                    state.credit(tx.from(), required - current);
                }
                state.applyTransaction(tx);
            }
        }
    }
}
