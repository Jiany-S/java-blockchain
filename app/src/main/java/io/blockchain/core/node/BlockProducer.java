package io.blockchain.core.node;

import io.blockchain.core.consensus.ProofOfWork;
import io.blockchain.core.consensus.ConsensusRules;
import io.blockchain.core.mempool.Mempool;
import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Merkle;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;

import java.util.List;
import java.util.Optional;

/**
 * Builds a block from mempool, runs PoW (optional), validates it, and persists it.
 */
public final class BlockProducer {

    private final ChainStore chain;
    private final Mempool mempool;
    private final ProofOfWork pow;
    private final long difficultyBits;  // interpret as leading zero bits
    private final int maxTxPerBlock;
    private final long maxTries;

    public BlockProducer(ChainStore chain, Mempool mempool, ProofOfWork pow,
                         long difficultyBits, int maxTxPerBlock, long maxTries) {
        this.chain = chain;
        this.mempool = mempool;
        this.pow = pow;
        this.difficultyBits = difficultyBits;
        this.maxTxPerBlock = maxTxPerBlock;
        this.maxTries = maxTries;
    }

    /** One production attempt: returns the new head hash if a block was produced. */
    public Optional<byte[]> tick() {
        // 1) Get parent info
        byte[] parentHash = chain.getHead().orElse(new byte[32]); // genesis parent = zeros
        long parentHeight = chain.getHead().isPresent()
                ? chain.getHeight(parentHash).orElse(0L)
                : -1L;

        long height = parentHeight + 1L;

        // 2) Gather txs from mempool
        List<Transaction> txs = mempool.getBatch(maxTxPerBlock);
        if (txs.isEmpty() && height > 0) {
            // nothing to do (unless you want to mine empty blocks)
            return Optional.empty();
        }

        // 3) Compute merkle root over tx IDs
        byte[] merkle = Merkle.rootOf(idsOf(txs));

        // 4) Build header template (nonce=0 to start)
        BlockHeader hdr = new BlockHeader(
                parentHash,
                merkle,
                height,
                System.currentTimeMillis(),
                difficultyBits,
                0L
        );
        Block template = new Block(hdr, txs);

        // 5) Mine (or accept as-is if difficulty=0)
        Block finalBlock;
        if (difficultyBits <= 0) {
            finalBlock = template;
        } else {
            Optional<Block> mined = pow.mine(template, maxTries);
            if (!mined.isPresent()) {
                // put txs back to mempool if mining failed (simple rollback)
                return Optional.empty();
            }
            finalBlock = mined.get();
        }

        // 6) Validate block before persisting
        ConsensusRules.validateBlock(finalBlock, chain);

        // 7) Persist and set head
        chain.putBlock(finalBlock);
        byte[] newHead = chain.getHead().orElse(null);

        // 8) Evict included txs from mempool
        mempool.removeAll(txs);

        return Optional.ofNullable(newHead);
    }

    private static java.util.List<byte[]> idsOf(List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<byte[]> out = new java.util.ArrayList<>(txs.size());
        for (Transaction tx : txs) out.add(tx.id());
        return out;
    }
}
