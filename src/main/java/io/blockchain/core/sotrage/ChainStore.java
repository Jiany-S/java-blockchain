package io.blockchain.core.storage;

import io.blockchain.core.protocol.Block;

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
}
