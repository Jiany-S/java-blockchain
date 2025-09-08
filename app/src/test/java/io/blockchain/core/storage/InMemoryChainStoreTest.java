package io.blockchain.core.storage;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import org.junit.jupiter.api.Test;

import java.util.Collections;

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
}
