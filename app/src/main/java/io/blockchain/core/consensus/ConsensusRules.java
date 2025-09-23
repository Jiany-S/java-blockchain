package io.blockchain.core.consensus;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Merkle;
import io.blockchain.core.storage.ChainStore;

import java.util.Arrays;
import java.util.Optional;

public final class ConsensusRules {
    private ConsensusRules() {}

    public static void validateBlock(Block block, ChainStore store) throws IllegalArgumentException {
        BlockHeader hdr = block.header();

        byte[] parentHash = hdr.parentHash();
        boolean genesisParent = isZeroHash(parentHash);

        long parentHeight;
        if (genesisParent) {
            parentHeight = -1L;
        } else {
            Optional<Long> parentHeightOpt = store.getHeight(parentHash);
            if (parentHeightOpt.isEmpty()) {
                throw new IllegalArgumentException("Unknown parent");
            }
            parentHeight = parentHeightOpt.get();
        }

        long expectedHeight = parentHeight + 1;
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

    private static boolean isZeroHash(byte[] hash) {
        if (hash == null) {
            return true;
        }
        for (byte b : hash) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
