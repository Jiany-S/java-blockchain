package io.blockchain.core.storage;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Hashes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simple, fast in-memory chain store.
 * Good for tests and local nodes before wiring RocksDB.
 *
 * Keys are byte[32] block hashes. We wrap them in a tiny BytesKey so they can be
 * used in hash maps safely (byte[] doesn't implement value-based equals/hashCode).
 */
public final class InMemoryChainStore implements ChainStore {

    /** Map: blockHash -> Block */
    private final Map<BytesKey, Block> blocks = new HashMap<>();

    /** Map: blockHash -> height (from header) */
    private final Map<BytesKey, Long> heights = new HashMap<>();

    /** Current head (best tip) */
    private byte[] head; // null until set

    @Override
    public synchronized void putBlock(Block block) {
        if (block == null) return;
        byte[] h = blockHash(block);
        BytesKey key = new BytesKey(h);

        // store block and height (idempotent)
        blocks.put(key, block);
        heights.put(key, block.header().height());

        // naive head rule for now: highest height wins, ties keep existing
        if (head == null) {
            head = h.clone();
            return;
        }
        long currentHeadHeight = heights.getOrDefault(new BytesKey(head), -1L);
        long newHeight = block.header().height();
        if (newHeight > currentHeadHeight) {
            head = h.clone();
        }
    }

    @Override
    public synchronized Optional<Block> getBlock(byte[] blockHash) {
        if (blockHash == null) return Optional.empty();
        Block blk = blocks.get(new BytesKey(blockHash));
        return Optional.ofNullable(blk);
    }

    @Override
    public synchronized Optional<byte[]> getHead() {
        if (head == null) return Optional.empty();
        return Optional.of(head.clone());
    }

    @Override
    public synchronized void setHead(byte[] blockHash) {
        if (blockHash == null) {
            head = null;
            return;
        }
        // only set if we actually know this block
        if (blocks.containsKey(new BytesKey(blockHash))) {
            head = blockHash.clone();
        } else {
            throw new IllegalArgumentException("Unknown head hash (store the block first)");
        }
    }

    @Override
    public synchronized Optional<Long> getHeight(byte[] blockHash) {
        if (blockHash == null) return Optional.empty();
        Long h = heights.get(new BytesKey(blockHash));
        return Optional.ofNullable(h);
    }

    @Override
    public synchronized long size() {
        return blocks.size();
    }

    /** Block hash helper: SHA-256(header.serialize()). */
    private static byte[] blockHash(Block block) {
        BlockHeader bh = block.header();
        return Hashes.sha256(bh.serialize());
    }

    /** Value-based key wrapper around byte[] so we can use it in maps/sets. */
    private static final class BytesKey {
        private final byte[] bytes;
        private final int hash; // cache hashCode

        BytesKey(byte[] src) {
            if (src == null) throw new IllegalArgumentException("null key");
            this.bytes = src.clone();
            this.hash = Arrays.hashCode(this.bytes);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BytesKey)) return false;
            BytesKey other = (BytesKey) o;
            return Arrays.equals(this.bytes, other.bytes);
        }

        @Override public int hashCode() {
            return hash;
        }
    }
}
