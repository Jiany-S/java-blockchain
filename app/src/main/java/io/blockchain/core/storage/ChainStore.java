package io.blockchain.core.storage;

import io.blockchain.core.protocol.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Minimal chain persistence API.
 * Stores blocks by their header hash, tracks heights and the current head.
 *
 * Notes:
 * - Block "hash" = SHA-256 of the BlockHeader serialization (see BlockHeader#hash()).
 * - Height is taken from the header for now; fork-choice will refine this later.
 */
public interface ChainStore {

    /** Persist a block (idempotent). Safe to call again with the same block. */
    void putBlock(Block block);

    /** Fetch a block by its hash (32 bytes). */
    Optional<Block> getBlock(byte[] blockHash);

    /** Return the current head hash if set. */
    Optional<byte[]> getHead();

    /** Force the head hash (used after fork-choice). */
    void setHead(byte[] blockHash);

    /** Height for a given block hash if known. */
    Optional<Long> getHeight(byte[] blockHash);

    /** Number of blocks stored (debug/metrics). */
    long size();

    // In ChainStore.java (interface)
    default Iterable<Block> getBlocksInOrder() {
        List<Block> blocks = new ArrayList<>();
        Optional<byte[]> headOpt = getHead();
        if (headOpt.isEmpty()) return blocks;

        // Walk backwards then reverse
        byte[] cursor = headOpt.get();
        while (cursor != null) {
            Optional<Block> blk = getBlock(cursor);
            if (blk.isEmpty()) break;
            blocks.add(blk.get());
            cursor = blk.get().header().parentHash();
            if (Arrays.equals(cursor, new byte[32])) break; // stop at genesis
        }
        Collections.reverse(blocks);
        return blocks;
    }

    default List<byte[]> getChildren(byte[] parentHash) {
        if (parentHash == null) {
            return Collections.emptyList();
        }
        List<byte[]> children = new ArrayList<>();
        for (Block block : getBlocksInOrder()) {
            if (Arrays.equals(block.header().parentHash(), parentHash)) {
                children.add(block.header().hash());
            }
        }
        return children;
    }

}
