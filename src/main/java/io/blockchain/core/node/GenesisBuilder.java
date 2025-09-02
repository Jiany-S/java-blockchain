package io.blockchain.core.node;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Merkle;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;
import io.blockchain.core.state.StateStore;

import java.util.Collections;
import java.util.Map;

/**
 * Creates the genesis block and seeds initial balances.
 * - Height = 0
 * - parentHash = 32 zero bytes
 * - merkleRoot of empty list (32 zero bytes for our MVP)
 * - difficulty = 0 (no PoW needed)
 */
public final class GenesisBuilder {
    private GenesisBuilder(){}

    /** Build a minimal empty genesis block. */
    public static Block buildGenesis() {
        byte[] zeros = new byte[32];
        BlockHeader hdr = new BlockHeader(
                zeros,                 // parentHash
                Merkle.rootOf(Collections.<byte[]>emptyList()),
                0L,                    // height
                System.currentTimeMillis(),
                0L,                    // difficultyOrSlot
                0L                     // nonce
        );
        return new Block(hdr, Collections.<Transaction>emptyList());
    }

    /** Credit initial balances (allocations map) into state. */
    public static void seedBalances(StateStore state, Map<String, Long> allocations) {
        if (allocations == null || allocations.isEmpty()) return;
        for (Map.Entry<String, Long> e : allocations.entrySet()) {
            state.credit(e.getKey(), e.getValue() == null ? 0L : e.getValue());
        }
    }

    /**
     * If the chain is empty, seed balances and store the genesis block.
     * Idempotent: does nothing if a head already exists.
     */
    public static void initIfNeeded(ChainStore chain, StateStore state, Map<String, Long> allocations) {
        if (chain.getHead().isPresent()) return;
        seedBalances(state, allocations);
        Block genesis = buildGenesis();
        chain.putBlock(genesis);
    }
}
