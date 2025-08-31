package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal header: everything needed to identify/verify a block without tx bodies.
 * - parentHash: link to previous block
 * - merkleRoot: commitment to all txs in this block
 * - height: block number (genesis = 0)
 * - timestamp: producer clock (sanity-checked later)
 * - difficultyOrSlot: PoW target bits OR slot index (kept generic for now)
 * - nonce: used by PoW (ignored by slot-based consensus)
 */
public final class BlockHeader {
    private final byte[] parentHash;     // 32 bytes
    private final byte[] merkleRoot;     // 32 bytes
    private final long height;
    private final long timestamp;
    private final long difficultyOrSlot; // generic knob
    private final long nonce;            // PoW mining only

    public BlockHeader(byte[] parentHash,
                       byte[] merkleRoot,
                       long height,
                       long timestamp,
                       long difficultyOrSlot,
                       long nonce) {
        this.parentHash = parentHash != null ? parentHash.clone() : new byte[32];
        this.merkleRoot = merkleRoot != null ? merkleRoot.clone() : new byte[32];
        this.height = height;
        this.timestamp = timestamp;
        this.difficultyOrSlot = difficultyOrSlot;
        this.nonce = nonce;
        basicValidate();
    }

    public byte[] parentHash() { return parentHash.clone(); }
    public byte[] merkleRoot() { return merkleRoot.clone(); }
    public long height() { return height; }
    public long timestamp() { return timestamp; }
    public long difficultyOrSlot() { return difficultyOrSlot; }
    public long nonce() { return nonce; }

    // Deterministic unsigned header bytes (no signature concept here)
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(estimateSize());
        putBytes(buf, parentHash);
        putBytes(buf, merkleRoot);
        putLong(buf, height);
        putLong(buf, timestamp);
        putLong(buf, difficultyOrSlot);
        putLong(buf, nonce);
        return slice(buf);
    }

    public byte[] hash() {
        return Hashes.sha256(serialize());
    }

    public void basicValidate() {
        if (parentHash.length != 32) throw new IllegalArgumentException("parentHash must be 32 bytes");
        if (merkleRoot.length != 32) throw new IllegalArgumentException("merkleRoot must be 32 bytes");
        if (height < 0) throw new IllegalArgumentException("height must be >= 0");
        if (timestamp <= 0) throw new IllegalArgumentException("timestamp must be > 0");
        // difficultyOrSlot/nonce sanity is consensus-specific; leave loose here
    }

    private int estimateSize() {
        return (4+parentHash.length) + (4+merkleRoot.length) + 8 + 8 + 8 + 8;
    }

    private static void putLong(ByteBuffer b, long v){ b.putLong(v); }
    private static void putBytes(ByteBuffer b, byte[] a){
        b.putInt(a == null ? 0 : a.length);
        if (a != null) b.put(a);
    }
    private static byte[] slice(ByteBuffer b){ b.flip(); byte[] out = new byte[b.remaining()]; b.get(out); return out; }

    @Override public String toString() {
        return "BlockHeader{h=" + height + ", ts=" + timestamp + "}";
    }
}
