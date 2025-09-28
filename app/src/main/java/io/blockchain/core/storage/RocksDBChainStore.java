package io.blockchain.core.storage;

import io.blockchain.core.consensus.ProofOfWork;
import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Hashes;
import org.rocksdb.*;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Persistent ChainStore using RocksDB.
 *
 * Layout (column families):
 *  - "blocks"  : key = blockHash(32), val = block.serialize()
 *  - "heights" : key = blockHash(32), val = height(8, big-endian)
 *  - "meta"    : key = "head",        val = blockHash(32)
 *  - "children": key = parentHash(32), val = child hashes (32 * n)
 *  - "work"    : key = blockHash(32), val = cumulative work (big-endian, 32 bytes)
 */
public final class RocksDBChainStore implements ChainStore, AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final RocksDB db;
    private final ColumnFamilyHandle cfBlocks;
    private final ColumnFamilyHandle cfHeights;
    private final ColumnFamilyHandle cfMeta;
    private final ColumnFamilyHandle cfChildren;
    private final ColumnFamilyHandle cfWork;

    private final Options options;
    private final DBOptions dbOptions;

    private RocksDBChainStore(RocksDB db,
                              ColumnFamilyHandle cfBlocks,
                              ColumnFamilyHandle cfHeights,
                              ColumnFamilyHandle cfMeta,
                              ColumnFamilyHandle cfChildren,
                              ColumnFamilyHandle cfWork,
                              Options options,
                              DBOptions dbOptions) {
        this.db = db;
        this.cfBlocks = cfBlocks;
        this.cfHeights = cfHeights;
        this.cfMeta = cfMeta;
        this.cfChildren = cfChildren;
        this.cfWork = cfWork;
        this.options = options;
        this.dbOptions = dbOptions;
    }

    /** Factory: open/create a store in the given directory path. */
    public static RocksDBChainStore open(String dataDir) {
        try {
            // default CF (ignored after we open named CFs)
            Options opts = new Options()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true);

            DBOptions dbOpts = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true);

            // declare CF descriptors we want
            ColumnFamilyDescriptor cfDefault = new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY);
            ColumnFamilyDescriptor cfBlocksDesc  = new ColumnFamilyDescriptor("blocks".getBytes());
            ColumnFamilyDescriptor cfHeightsDesc = new ColumnFamilyDescriptor("heights".getBytes());
            ColumnFamilyDescriptor cfMetaDesc    = new ColumnFamilyDescriptor("meta".getBytes());
            ColumnFamilyDescriptor cfChildrenDesc = new ColumnFamilyDescriptor("children".getBytes());
            ColumnFamilyDescriptor cfWorkDesc = new ColumnFamilyDescriptor("work".getBytes());

            java.util.List<ColumnFamilyDescriptor> cfDescs = java.util.Arrays.asList(
                    cfDefault, cfBlocksDesc, cfHeightsDesc, cfMetaDesc, cfChildrenDesc, cfWorkDesc
            );
            java.util.List<ColumnFamilyHandle> cfHandles = new java.util.ArrayList<>();

            RocksDB db = RocksDB.open(dbOpts, dataDir, cfDescs, cfHandles);

            // Keep handles for named CFs (skip the default at index 0)
            ColumnFamilyHandle cfBlocks = cfHandles.get(1);
            ColumnFamilyHandle cfHeights = cfHandles.get(2);
            ColumnFamilyHandle cfMeta = cfHandles.get(3);
            ColumnFamilyHandle cfChildren = cfHandles.get(4);
            ColumnFamilyHandle cfWork = cfHandles.get(5);

            return new RocksDBChainStore(db, cfBlocks, cfHeights, cfMeta, cfChildren, cfWork, opts, dbOpts);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB at " + dataDir, e);
        }
    }

    // -------------- ChainStore API ----------------

    @Override
    public synchronized void putBlock(Block block) {
        if (block == null) return;
        BlockHeader header = block.header();
        byte[] hash = blockHash(block); // 32 bytes
        byte[] height = longToBytes(header.height());
        byte[] body = block.serialize();

        try (WriteOptions wo = new WriteOptions().setSync(false)) {
            // batch for atomicity
            try (WriteBatch batch = new WriteBatch()) {
                batch.put(cfBlocks, hash, body);
                batch.put(cfHeights, hash, height);

                byte[] parent = header.parentHash();
                if (parent != null) {
                    byte[] existingChildren = db.get(cfChildren, parent);
                    byte[] updated = appendChild(existingChildren, hash);
                    batch.put(cfChildren, parent, updated);
                }

                BigInteger parentWork = BigInteger.ZERO;
                if (parent != null) {
                    byte[] workBytes = db.get(cfWork, parent);
                    if (workBytes != null) {
                        parentWork = bytesToBigInteger(workBytes);
                    }
                }
                BigInteger blockWork = ProofOfWork.calculateBlockWork(header);
                BigInteger totalWork = parentWork.add(blockWork);
                batch.put(cfWork, hash, bigIntegerToBytes(totalWork));

                // heaviest-chain rule: highest cumulative work wins, tie-break by height
                byte[] currentHead = db.get(cfMeta, "head".getBytes());
                boolean updateHead = false;
                if (currentHead == null) {
                    updateHead = true;
                } else {
                    BigInteger currentWork = BigInteger.ZERO;
                    byte[] currentWorkBytes = db.get(cfWork, currentHead);
                    if (currentWorkBytes != null) {
                        currentWork = bytesToBigInteger(currentWorkBytes);
                    }
                    int cmp = totalWork.compareTo(currentWork);
                    if (cmp > 0) {
                        updateHead = true;
                    } else if (cmp == 0) {
                        long currentHeadH = -1L;
                        byte[] hh = db.get(cfHeights, currentHead);
                        if (hh != null) currentHeadH = bytesToLong(hh);
                        if (header.height() > currentHeadH) {
                            updateHead = true;
                        }
                    }
                }
                if (updateHead) {
                    batch.put(cfMeta, "head".getBytes(), hash);
                }
                db.write(wo, batch);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("putBlock failed", e);
        }
    }

    @Override
    public synchronized Optional<Block> getBlock(byte[] blockHash) {
        if (blockHash == null) return Optional.empty();
        try {
            byte[] body = db.get(cfBlocks, blockHash);
            if (body == null) return Optional.empty();
            // You already have BlockCodec in protocol; use it if present.
            // Avoiding a hard dependency here: import your codec.
            return Optional.of(io.blockchain.core.protocol.BlockCodec.fromBytes(body));
        } catch (Exception e) {
            throw new RuntimeException("getBlock failed", e);
        }
    }

    @Override
    public synchronized Optional<byte[]> getHead() {
        try {
            byte[] head = db.get(cfMeta, "head".getBytes());
            return head == null ? Optional.empty() : Optional.of(head.clone());
        } catch (RocksDBException e) {
            throw new RuntimeException("getHead failed", e);
        }
    }

    @Override
    public synchronized void setHead(byte[] blockHash) {
        try {
            if (blockHash == null) {
                db.delete(cfMeta, "head".getBytes());
                return;
            }
            // only set if exists
            byte[] exists = db.get(cfBlocks, blockHash);
            if (exists == null) throw new IllegalArgumentException("Unknown head hash");
            db.put(cfMeta, "head".getBytes(), blockHash);
        } catch (RocksDBException e) {
            throw new RuntimeException("setHead failed", e);
        }
    }

    @Override
    public synchronized Optional<Long> getHeight(byte[] blockHash) {
        if (blockHash == null) return Optional.empty();
        try {
            byte[] h = db.get(cfHeights, blockHash);
            return h == null ? Optional.empty() : Optional.of(bytesToLong(h));
        } catch (RocksDBException e) {
            throw new RuntimeException("getHeight failed", e);
        }
    }

    @Override
    public synchronized Optional<BigInteger> getTotalWork(byte[] blockHash) {
        if (blockHash == null) return Optional.empty();
        try {
            byte[] data = db.get(cfWork, blockHash);
            return data == null ? Optional.empty() : Optional.of(bytesToBigInteger(data));
        } catch (RocksDBException e) {
            throw new RuntimeException("getTotalWork failed", e);
        }
    }

    @Override
    public synchronized long size() {
        // Rough estimate: number of blocks = number of keys in "blocks" CF.
        // RocksJava doesn't expose a simple size; use property or an iterator.
        try (RocksIterator it = db.newIterator(cfBlocks)) {
            long n = 0;
            for (it.seekToFirst(); it.isValid(); it.next()) n++;
            return n;
        }
    }

    @Override
    public void close() {
        // Close CF handles first, then DB/options
        try { if (cfBlocks != null) cfBlocks.close(); } catch (Exception ignored) {}
        try { if (cfHeights != null) cfHeights.close(); } catch (Exception ignored) {}
        try { if (cfMeta != null) cfMeta.close(); } catch (Exception ignored) {}
        try { if (cfChildren != null) cfChildren.close(); } catch (Exception ignored) {}
        try { if (cfWork != null) cfWork.close(); } catch (Exception ignored) {}
        try { if (db != null) db.close(); } catch (Exception ignored) {}
        try { if (options != null) options.close(); } catch (Exception ignored) {}
        try { if (dbOptions != null) dbOptions.close(); } catch (Exception ignored) {}
    }

    @Override
    public synchronized List<byte[]> getChildren(byte[] parentHash) {
        if (parentHash == null) {
            return Collections.emptyList();
        }
        try {
            byte[] data = db.get(cfChildren, parentHash);
            if (data == null || data.length == 0) {
                return Collections.emptyList();
            }
            int count = data.length / 32;
            List<byte[]> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int start = i * 32;
                int end = start + 32;
                out.add(Arrays.copyOfRange(data, start, end));
            }
            return out;
        } catch (RocksDBException e) {
            throw new RuntimeException("getChildren failed", e);
        }
    }

    // -------------- helpers ----------------

    private static byte[] appendChild(byte[] existing, byte[] child) {
        if (existing != null) {
            for (int i = 0; i < existing.length; i += 32) {
                boolean match = true;
                for (int j = 0; j < 32; j++) {
                    if (existing[i + j] != child[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return existing;
                }
            }
        }
        byte[] updated = new byte[(existing == null ? 0 : existing.length) + child.length];
        if (existing != null) {
            System.arraycopy(existing, 0, updated, 0, existing.length);
        }
        System.arraycopy(child, 0, updated, existing == null ? 0 : existing.length, child.length);
        return updated;
    }

    private static byte[] blockHash(Block block) {
        BlockHeader bh = block.header();
        return Hashes.sha256(bh.serialize());
    }

    private static byte[] longToBytes(long v) {
        ByteBuffer b = ByteBuffer.allocate(8);
        b.putLong(v);
        return b.array();
    }

    private static long bytesToLong(byte[] a) {
        ByteBuffer b = ByteBuffer.wrap(a);
        return b.getLong();
    }

    private static byte[] bigIntegerToBytes(BigInteger value) {
        if (value == null || value.signum() < 0) {
            value = BigInteger.ZERO;
        }
        byte[] raw = value.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static BigInteger bytesToBigInteger(byte[] data) {
        if (data == null || data.length == 0) {
            return BigInteger.ZERO;
        }
        return new BigInteger(1, data);
    }
}
