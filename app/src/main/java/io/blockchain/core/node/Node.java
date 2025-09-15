package io.blockchain.core.node;

import io.blockchain.core.consensus.ProofOfWork;
import io.blockchain.core.mempool.Mempool;
import io.blockchain.core.mempool.TxValidator;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;
import io.blockchain.core.storage.InMemoryChainStore;
import io.blockchain.core.storage.RocksDBChainStore;
import io.blockchain.core.state.InMemoryStateStore;
import io.blockchain.core.state.StateReplayer;
import io.blockchain.core.state.StateStore;

import java.util.Optional;

/**
 * Wires state, storage, mempool, consensus, and the block producer.
 * Start once, then call tick() periodically to try to produce a block.
 */
public final class Node {

    private final ChainStore chain;
    private final StateStore state;
    private final Mempool mempool;
    private final ProofOfWork pow;
    private final BlockProducer producer;
    private final NodeConfig config;

    public Node(ChainStore chain, StateStore state, Mempool mempool, ProofOfWork pow, NodeConfig config) {
        this.chain = chain;
        this.state = state;
        this.mempool = mempool;
        this.pow = pow;
        this.config = config;
        this.producer = new BlockProducer(
                chain, mempool, pow,
                config.difficultyBits,
                config.maxTxPerBlock,
                config.maxPowTries
        );
    }

    /** Convenience factory for an in-memory local node. */
    public static Node inMemory(NodeConfig config) {
        StateStore state = new InMemoryStateStore();
        TxValidator validator = new TxValidator(state, 1L);
        Mempool mempool = new Mempool(validator);
        ChainStore chain = new InMemoryChainStore();
        ProofOfWork pow = new ProofOfWork();
        return new Node(chain, state, mempool, pow, config);
    }

    /** Ensure genesis exists and balances are seeded. Safe to call multiple times. */
    public void start() {
        if (chain.getHead().isPresent()) {
            // Existing chain: replay state
            StateReplayer.replay(chain, state);
        } else {
            // Empty chain: genesis + seed
            GenesisBuilder.seedBalances(state, config.genesisAllocations);
            GenesisBuilder.initIfNeeded(chain, state, config.genesisAllocations);
        }
    }

    /** Try to produce one block (returns new head hash if produced). */
    public Optional<byte[]> tick() {
        return this.producer.tick();
    }

    /** Convenience factory for a RocksDB-backed node. */
    public static Node rocks(NodeConfig config, String dataDir) {
        StateStore state = new InMemoryStateStore();
        TxValidator validator = new TxValidator(state, 1L);
        Mempool mempool = new Mempool(validator);
        ChainStore chain = RocksDBChainStore.open(dataDir);
        ProofOfWork pow = new ProofOfWork();
        return new Node(chain, state, mempool, pow, config);
    }

    /** Close underlying resources if any (e.g., RocksDB). */
    public void close() {
        try {
            if (chain instanceof AutoCloseable) {
                ((AutoCloseable) chain).close();
            }
        } catch (Exception ignored) {}
    }

    // Properly typed accessors
    public ChainStore chain() { return chain; }
    public StateStore state() { return state; }
    public Mempool mempool() { return mempool; }
}
