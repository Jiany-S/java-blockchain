package io.blockchain.core.consensus;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Merkle;
import io.blockchain.core.storage.ChainStore;

import java.util.Arrays;

public final class ConsensusRules {
    private ConsensusRules() {}

    public static void validateBlock(Block block, ChainStore store) throws IllegalArgumentException {
        BlockHeader hdr = block.header();

        // 1) Parent must exist (unless genesis)
        if (store.getHead().isPresent()) {
            byte[] parentHash = hdr.parentHash();
            if (store.getHeight(parentHash).isEmpty()) {
                throw new IllegalArgumentException("Unknown parent");
            }
        }

        // 2) Height = parent height + 1
        long expectedHeight = store.getHead()
                .map(h -> store.getHeight(h).orElse(-1L))
                .orElse(-1L) + 1;
        if (hdr.height() != expectedHeight) {
            throw new IllegalArgumentException("Bad block height: expected " + expectedHeight + ", got " + hdr.height());
        }

        // 3) Merkle root must match
        byte[] computed = Merkle.rootOf(block.transactions().stream().map(tx -> tx.id()).toList());
        if (!Arrays.equals(hdr.merkleRoot(), computed)) {
            throw new IllegalArgumentException("Merkle mismatch");
        }

        // 4) Difficulty check
        ProofOfWork pow = new ProofOfWork();
        if (!pow.meetsTarget(hdr)) {
            throw new IllegalArgumentException("Proof-of-Work target not met");
        }

        // 5) Timestamp sanity
        long now = System.currentTimeMillis();
        if (hdr.timestamp() > now + 60_000L) {
            throw new IllegalArgumentException("Timestamp too far in future");
        }
    }
}
