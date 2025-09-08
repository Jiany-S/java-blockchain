package io.blockchain.core.storage;

import java.io.Closeable;

/**
 * Minimal key–value API so we can swap implementations (RocksDB, in-memory, etc.).
 * Keys/values are raw bytes; callers handle encoding (e.g., long -> 8 bytes).
 */
public interface KeyValueDB extends Closeable {
    byte[] get(byte[] key) throws Exception;
    void put(byte[] key, byte[] value) throws Exception;
    void delete(byte[] key) throws Exception;
    @Override void close();
}
