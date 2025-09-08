package io.blockchain.core.node;

import java.util.HashMap;
import java.util.Map;

/** Simple config holder for a local node. */
public final class NodeConfig {
    public final long difficultyBits;
    public final int maxTxPerBlock;
    public final long maxPowTries;
    public final Map<String, Long> genesisAllocations;

    public NodeConfig(long difficultyBits, int maxTxPerBlock, long maxPowTries, Map<String, Long> genesisAllocations) {
        this.difficultyBits = difficultyBits;
        this.maxTxPerBlock = maxTxPerBlock;
        this.maxPowTries = maxPowTries;
        this.genesisAllocations = genesisAllocations;
    }

    public static NodeConfig defaultLocal() {
        Map<String, Long> alloc = new HashMap<String, Long>();
        alloc.put("alice123456", 1_000_000L);
        alloc.put("bob654321",     500_000L);
        return new NodeConfig(
                12L,          // easy PoW for local mining
                1000,         // tx per block cap for MVP
                2_000_000L,   // max nonce attempts per tick
                alloc
        );
    }
}
