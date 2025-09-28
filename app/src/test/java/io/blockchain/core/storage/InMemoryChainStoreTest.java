package io.blockchain.core.storage;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
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
        BigInteger work = store.getTotalWork(store.getHead().get()).orElse(BigInteger.ZERO);
        assertTrue(work.compareTo(BigInteger.ZERO) > 0);
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

    @Test
    void prefersChainWithMoreWorkOverHigherHeight() {
        InMemoryChainStore store = new InMemoryChainStore();
        byte[] zeros = new byte[32];
        long now = System.currentTimeMillis();

        BlockHeader genesisHeader = new BlockHeader(zeros, zeros, 0, now, 8, 0);
        Block genesis = new Block(genesisHeader, Collections.emptyList());
        store.putBlock(genesis);

        BlockHeader light1 = new BlockHeader(genesisHeader.hash(), zeros, 1, now + 1000, 8, 0);
        Block blockLight1 = new Block(light1, Collections.emptyList());
        store.putBlock(blockLight1);

        BlockHeader light2 = new BlockHeader(light1.hash(), zeros, 2, now + 2000, 8, 0);
        Block blockLight2 = new Block(light2, Collections.emptyList());
        store.putBlock(blockLight2);

        assertArrayEquals(blockLight2.header().hash(), store.getHead().orElseThrow());

        BlockHeader heavy = new BlockHeader(genesisHeader.hash(), zeros, 1, now + 3000, 24, 0);
        Block blockHeavy = new Block(heavy, Collections.emptyList());
        store.putBlock(blockHeavy);

        assertArrayEquals(blockHeavy.header().hash(), store.getHead().orElseThrow());

        BigInteger lightWork = store.getTotalWork(blockLight2.header().hash()).orElse(BigInteger.ZERO);
        BigInteger heavyWork = store.getTotalWork(blockHeavy.header().hash()).orElse(BigInteger.ZERO);
        assertTrue(heavyWork.compareTo(lightWork) > 0);
    }
}
