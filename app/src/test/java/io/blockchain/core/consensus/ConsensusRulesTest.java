package io.blockchain.core.consensus;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.storage.InMemoryChainStore;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsensusRulesTest {

    @Test
    void rejectsUnknownParent() {
        InMemoryChainStore store = new InMemoryChainStore();
        byte[] parent = new byte[32];
        parent[0] = 1;
        long now = System.currentTimeMillis();
        BlockHeader header = new BlockHeader(parent, new byte[32], 1, now, 0, 0);
        Block block = new Block(header, Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> ConsensusRules.validateBlock(block, store));
    }

    @Test
    void rejectsTimestampBeforeParent() {
        InMemoryChainStore store = new InMemoryChainStore();
        byte[] zeros = new byte[32];
        long now = System.currentTimeMillis();

        BlockHeader genesisHeader = new BlockHeader(zeros, zeros, 0, now, 0, 0);
        Block genesis = new Block(genesisHeader, Collections.emptyList());
        store.putBlock(genesis);

        BlockHeader childHeader = new BlockHeader(genesisHeader.hash(), zeros, 1, now - 1_000, 0, 0);
        Block child = new Block(childHeader, Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> ConsensusRules.validateBlock(child, store));
    }

    @Test
    void acceptsValidHeader() {
        InMemoryChainStore store = new InMemoryChainStore();
        byte[] zeros = new byte[32];
        long now = System.currentTimeMillis();

        BlockHeader genesisHeader = new BlockHeader(zeros, zeros, 0, now, 0, 0);
        Block genesis = new Block(genesisHeader, Collections.emptyList());
        store.putBlock(genesis);

        BlockHeader childHeader = new BlockHeader(genesisHeader.hash(), zeros, 1, now + 1_000, 0, 0);
        Block child = new Block(childHeader, Collections.emptyList());

        assertDoesNotThrow(() -> ConsensusRules.validateBlock(child, store));
    }
}
