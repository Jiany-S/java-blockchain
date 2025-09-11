package io.blockchain.core.state;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;

public final class StateReplayer {
    private StateReplayer(){}

    public static void replay(ChainStore chain, StateStore state) {
        state.setBalance("alice123456", 1_000_000L);
        state.setBalance("bob654321", 0L);
        for (Block block : chain.getBlocksInOrder()) {
            for (Transaction tx : block.transactions()) {
                state.applyTransaction(tx);
            }
        }
    }

}
