package io.blockchain.core.node;

import io.blockchain.core.consensus.ProofOfWork;
import io.blockchain.core.consensus.ConsensusRules;
import io.blockchain.core.mempool.Mempool;
import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Merkle;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.state.StateStore;
import io.blockchain.core.storage.ChainStore;

import java.util.List;
import java.util.Optional;

import static java.lang.Math.addExact;

/**
 * Builds a block from mempool, runs PoW (optional), validates it, and persists it.
 */
public final class BlockProducer {

    private final ChainStore chain;
    private final StateStore state;
    private final Mempool mempool;
    private final ProofOfWork pow;
    private final long difficultyBits;  // interpret as leading zero bits
    private final int maxTxPerBlock;
    private final long maxTries;
    private final String minerAddress;
    private final long blockRewardMinor;

    public BlockProducer(ChainStore chain, StateStore state, Mempool mempool, ProofOfWork pow,
                         long difficultyBits, int maxTxPerBlock, long maxTries,
                         String minerAddress, long blockRewardMinor) {
        this.chain = chain;
        this.state = state;
        this.mempool = mempool;
        this.pow = pow;
        this.difficultyBits = difficultyBits;
        this.maxTxPerBlock = maxTxPerBlock;
        this.maxTries = maxTries;
        this.minerAddress = (minerAddress == null || minerAddress.isBlank()) ? null : minerAddress;
        this.blockRewardMinor = Math.max(0L, blockRewardMinor);
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

        long totalFeesMinor = sumFees(txs);
        long rewardMinor = computeReward(totalFeesMinor);

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
                requeueTransactions(txs);
                return Optional.empty();
            }
            finalBlock = mined.get();
        }

        boolean stateApplied = false;
        boolean rewardCredited = false;
        try {
            // 6) Validate block before persisting
            ConsensusRules.validateBlock(finalBlock, chain);

            // 7) Apply state and persist
            state.applyBlock(finalBlock);
            stateApplied = true;
            if (rewardMinor > 0 && minerAddress != null) {
                state.credit(minerAddress, rewardMinor);
                rewardCredited = true;
            }
            chain.putBlock(finalBlock);
            byte[] newHead = chain.getHead().orElse(null);

            // 8) Evict included txs from mempool
            mempool.removeAll(txs);

            return Optional.ofNullable(newHead);
        } catch (RuntimeException e) {
            if (stateApplied) {
                try {
                    state.revertBlock(finalBlock);
                } catch (Exception ignored) {
                }
            }
            if (rewardCredited) {
                try {
                    state.credit(minerAddress, -rewardMinor);
                } catch (Exception ignored) {
                }
            }
            requeueTransactions(txs);
            throw e;
        }
    }

    private static java.util.List<byte[]> idsOf(List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<byte[]> out = new java.util.ArrayList<>(txs.size());
        for (Transaction tx : txs) out.add(tx.id());
        return out;
    }

    private long sumFees(List<Transaction> txs) {
        long total = 0L;
        if (txs == null) {
            return 0L;
        }
        for (Transaction tx : txs) {
            try {
                total = addExact(total, Math.max(0L, tx.feeMinor()));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Fee total overflow", e);
            }
        }
        return total;
    }

    private long computeReward(long totalFeesMinor) {
        if (minerAddress == null) {
            return 0L;
        }
        try {
            return addExact(blockRewardMinor, totalFeesMinor);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Reward overflow", e);
        }
    }

    private void requeueTransactions(List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return;
        }
        for (Transaction tx : txs) {
            try {
                mempool.add(tx);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
