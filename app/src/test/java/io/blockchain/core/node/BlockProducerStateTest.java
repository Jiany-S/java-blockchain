package io.blockchain.core.node;

import io.blockchain.core.consensus.ProofOfWork;
import io.blockchain.core.mempool.Mempool;
import io.blockchain.core.mempool.TxValidator;
import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;
import io.blockchain.core.storage.InMemoryChainStore;
import io.blockchain.core.state.InMemoryStateStore;
import io.blockchain.core.state.StateStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BlockProducerStateTest {

    @Test
    void appliesStateWhenBlockProduced() {
        StateStore state = new InMemoryStateStore();
        state.setBalance("alice", 100);
        state.setNonce("alice", 0);

        TxValidator validator = new TxValidator(state, 0);
        Mempool mempool = new Mempool(validator);

        Transaction tx = Transaction.builder()
                .from("alice")
                .to("bob")
                .amountMinor(25)
                .feeMinor(2)
                .nonce(0)
                .build();
        assertTrue(mempool.add(tx));

        InMemoryChainStore chain = new InMemoryChainStore();
        BlockProducer producer = new BlockProducer(chain, state, mempool, new ProofOfWork(), 0, 16, 1000, "miner", 50);

        Optional<byte[]> head = producer.tick();
        assertTrue(head.isPresent());
        assertEquals(73, state.getBalance("alice"));
        assertEquals(25, state.getBalance("bob"));
        assertEquals(1, state.getNonce("alice"));
        assertEquals(50 + 2, state.getBalance("miner"));
        assertEquals(0, mempool.size());
    }

    @Test
    void revertsStateWhenPersistenceFails() {
        StateStore state = new InMemoryStateStore();
        state.setBalance("alice", 100);
        state.setNonce("alice", 0);

        TxValidator validator = new TxValidator(state, 0);
        Mempool mempool = new Mempool(validator);

        Transaction tx = Transaction.builder()
                .from("alice")
                .to("bob")
                .amountMinor(10)
                .feeMinor(1)
                .nonce(0)
                .build();
        assertTrue(mempool.add(tx));

        ChainStore chain = new FailingChainStore();
        BlockProducer producer = new BlockProducer(chain, state, mempool, new ProofOfWork(), 0, 16, 1000, "miner", 50);

        RuntimeException ex = assertThrows(RuntimeException.class, producer::tick);
        assertEquals("persist-failure", ex.getMessage());
        assertEquals(100, state.getBalance("alice"));
        assertEquals(0, state.getNonce("alice"));
        assertEquals(0, state.getBalance("bob"));
        assertEquals(0, state.getBalance("miner"));
        assertEquals(1, mempool.size());
    }

    private static final class FailingChainStore implements ChainStore {
        @Override
        public void putBlock(Block block) {
            throw new RuntimeException("persist-failure");
        }

        @Override
        public Optional<Block> getBlock(byte[] blockHash) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> getHead() {
            return Optional.empty();
        }

        @Override
        public void setHead(byte[] blockHash) {
        }

        @Override
        public Optional<Long> getHeight(byte[] blockHash) {
            return Optional.empty();
        }

        @Override
        public long size() {
            return 0;
        }
    }
}
