package io.blockchain.core.storage;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryChainStoreTest {

    @Test
    void putGetHead() {
        ChainStore store = new InMemoryChainStore();

        byte[] zeros = new byte[32];
        BlockHeader h0 = new BlockHeader(zeros, zeros, 0, System.currentTimeMillis(), 0, 0);
        Block b0 = new Block(h0, Collections.emptyList());

        store.putBlock(b0);
        assertEquals(1, store.size());
        assertTrue(store.getHead().isPresent());

        long height = store.getHeight(store.getHead().get()).orElse(-1L);
        assertEquals(0L, height);
    }

    @Test
    void tracksChildrenByParentHash() {
        InMemoryChainStore store = new InMemoryChainStore();

        byte[] zeros = new byte[32];
        BlockHeader genesisHeader = new BlockHeader(zeros, zeros, 0, System.currentTimeMillis(), 0, 0);
        Block genesis = new Block(genesisHeader, Collections.emptyList());
        store.putBlock(genesis);

        BlockHeader childHeader = new BlockHeader(genesisHeader.hash(), zeros, 1, System.currentTimeMillis(), 0, 0);
        Block child = new Block(childHeader, Collections.emptyList());
        store.putBlock(child);

        List<byte[]> children = store.getChildren(genesisHeader.hash());
        assertEquals(1, children.size());
        assertArrayEquals(childHeader.hash(), children.get(0));
    }
}
