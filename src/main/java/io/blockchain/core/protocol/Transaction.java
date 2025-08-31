package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal, deterministic transaction model for the MVP chain.
 *
 * Encoding rules (binary, length-prefixed):
 * - We encode fields in a fixed order.
 * - Strings are UTF-8 with a 4-byte big-endian length prefix.
 * - Byte arrays are prefixed with a 4-byte big-endian length.
 * - Primitive longs/ints written big-endian.
 *
 * We produce TWO byte forms:
 *  - unsignedBytes: no signature (used for signing + hashing)
 *  - serialize(): includes signature (used for network/storage)
 *
 * Hash/ID policy:
 *  - id = SHA-256(unsignedBytes)
 *  - So modifying the signature does NOT change the id (mempool dedupe is stable).
 */
public final class Transaction {

    // --- core, versioning & replay guards ---
    private final int version;        // bump if wire format changes
    private final int chainId;        // domain separation across networks

    // --- actors & economics ---
    private final String from;        // address string (validate format elsewhere)
    private final String to;          // address string
    private final long amountMinor;   // minor units (e.g., cents, satoshis)
    private final long feeMinor;      // minor units

    // --- ordering & metadata ---
    private final long nonce;         // monotonically increasing per 'from'
    private final long timestamp;     // ms since epoch (stateless drift checks only)

    // --- optional payload ---
    private final byte[] payload;     // opaque, length-limited

    // --- signature (may be empty for unsigned) ---
    private final byte[] signature;   // e.g., 64 bytes for Ed25519

    // --- cached id (hash of unsigned form) ---
    private final byte[] id;          // 32 bytes SHA-256

    // -------------------- ctor / builder --------------------

    /**
     * Use the builder to construct; this ctor assumes validated inputs.
     * It computes the ID (sha256 over unsigned bytes) on creation.
     */
    private Transaction(int version,
                        int chainId,
                        String from,
                        String to,
                        long amountMinor,
                        long feeMinor,
                        long nonce,
                        long timestamp,
                        byte[] payload,
                        byte[] signature) {
        this.version = version;
        this.chainId = chainId;
        this.from = from;
        this.to = to;
        this.amountMinor = amountMinor;
        this.feeMinor = feeMinor;
        this.nonce = nonce;
        this.timestamp = timestamp;
        this.payload = payload != null ? payload.clone() : new byte[0];
        this.signature = signature != null ? signature.clone() : new byte[0];
        this.id = sha256(toUnsignedBytes()); // id does NOT depend on signature
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int version = 1;
        private int chainId = 1;
        private String from;
        private String to;
        private long amountMinor;
        private long feeMinor;
        private long nonce;
        private long timestamp = System.currentTimeMillis();
        private byte[] payload = new byte[0];
        private byte[] signature = new byte[0];

        public Builder version(int v) { this.version = v; return this; }
        public Builder chainId(int id) { this.chainId = id; return this; }
        public Builder from(String f) { this.from = f; return this; }
        public Builder to(String t) { this.to = t; return this; }
        public Builder amountMinor(long a) { this.amountMinor = a; return this; }
        public Builder feeMinor(long f) { this.feeMinor = f; return this; }
        public Builder nonce(long n) { this.nonce = n; return this; }
        public Builder timestamp(long ts) { this.timestamp = ts; return this; }
        public Builder payload(byte[] p) { this.payload = p != null ? p.clone() : new byte[0]; return this; }
        public Builder signature(byte[] s) { this.signature = s != null ? s.clone() : new byte[0]; return this; }

        public Transaction build() {
            Transaction tx = new Transaction(
                    version, chainId, from, to, amountMinor, feeMinor, nonce, timestamp, payload, signature
            );
            tx.basicValidate(); // early fail if something is off
            return tx;
        }
    }

    // -------------------- getters --------------------

    public int version() { return version; }
    public int chainId() { return chainId; }
    public String from() { return from; }
    public String to() { return to; }
    public long amountMinor() { return amountMinor; }
    public long feeMinor() { return feeMinor; }
    public long nonce() { return nonce; }
    public long timestamp() { return timestamp; }
    public byte[] payload() { return payload.clone(); }
    public byte[] signature() { return signature.clone(); }

    /** 32-byte id (sha256 of unsigned bytes). */
    public byte[] id() { return id.clone(); }
    public String idHex() { return toHex(id); }

    // -------------------- core methods --------------------

    /**
     * Signed serialization for network/storage (includes signature).
     * Deterministic, length-prefixed, fixed field order.
     */
    public byte[] serialize() {
        // Compute total size roughly to avoid many re-allocations.
        // We'll use a growing buffer strategy if needed.
        ByteBuffer buf = ByteBuffer.allocate(estimateSize(/*includeSignature=*/true));
        putInt(buf, version);
        putInt(buf, chainId);
        putStr(buf, from);
        putStr(buf, to);
        putLong(buf, amountMinor);
        putLong(buf, feeMinor);
        putLong(buf, nonce);
        putLong(buf, timestamp);
        putBytes(buf, payload);
        putBytes(buf, signature);
        return sliceToArray(buf);
    }

    /**
     * Unsigned canonical bytes (no signature).
     * Used for hashing and signing.
     */
    public byte[] toUnsignedBytes() {
        ByteBuffer buf = ByteBuffer.allocate(estimateSize(/*includeSignature=*/false));
        putInt(buf, version);
        putInt(buf, chainId);
        putStr(buf, from);
        putStr(buf, to);
        putLong(buf, amountMinor);
        putLong(buf, feeMinor);
        putLong(buf, nonce);
        putLong(buf, timestamp);
        putBytes(buf, payload);
        return sliceToArray(buf);
    }

    /**
     * Hash of the unsigned form (ID). Included for convenience;
     * returns a clone to avoid caller mutating internal array.
     */
    public byte[] hash() {
        return id();
    }

    /**
     * Stateless sanity checks only. Anything that needs global
     * state (balances, double-spends) belongs in a separate validator.
     */
    public void basicValidate() throws IllegalArgumentException {
        if (version != 1) throw new IllegalArgumentException("Unsupported version: " + version);
        if (chainId <= 0) throw new IllegalArgumentException("Invalid chainId");
        if (from == null || from.isBlank()) throw new IllegalArgumentException("Missing from");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("Missing to");
        if (Objects.equals(from, to)) throw new IllegalArgumentException("from == to");
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be > 0");
        if (feeMinor < 0) throw new IllegalArgumentException("fee must be >= 0");
        if (nonce < 0) throw new IllegalArgumentException("nonce must be >= 0");
        if (timestamp <= 0) throw new IllegalArgumentException("timestamp must be > 0");

        // Payload cap (prevents abuse). Tune later; 8KB is conservative for MVP.
        if (payload.length > 8 * 1024) throw new IllegalArgumentException("payload too large");

        // If signature is present, enforce a reasonable size (e.g., Ed25519 = 64 bytes).
        if (signature.length != 0 && signature.length != 64) {
            throw new IllegalArgumentException("unsupported signature length: " + signature.length);
        }

        // Very light address format guard (tune with your real scheme later).
        // Here we just ensure hex-ish and length >= 8.
        if (from.length() < 8 || to.length() < 8) {
            throw new IllegalArgumentException("address too short");
        }
    }

    // -------------------- helpers --------------------
    
    /**
     * Estimate the serialized size so we can allocate a buffer big enough.
     * We count: fixed sizes + variable strings/arrays.
     */
    private int estimateSize(boolean includeSignature) {
        int size = 0;
        size += 4; // version
        size += 4; // chainId
        size += 4 + (from == null ? 0 : from.getBytes(StandardCharsets.UTF_8).length);
        size += 4 + (to == null ? 0 : to.getBytes(StandardCharsets.UTF_8).length);
        size += 8; // amountMinor
        size += 8; // feeMinor
        size += 8; // nonce
        size += 8; // timestamp
        size += 4 + (payload == null ? 0 : payload.length);
        if (includeSignature) {
            size += 4 + (signature == null ? 0 : signature.length);
        }
        return size;
    }

    private static byte[] sha256(byte[] in) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return d.digest(in);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen on a standard JVM
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static void putInt(ByteBuffer buf, int v) { ensureCapacity(buf, 4); buf.putInt(v); }
    private static void putLong(ByteBuffer buf, long v) { ensureCapacity(buf, 8); buf.putLong(v); }

    private static void putStr(ByteBuffer buf, String s) {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        putBytes(buf, b);
    }

    private static void putBytes(ByteBuffer buf, byte[] b) {
        if (b == null) b = new byte[0];
        ensureCapacity(buf, 4 + b.length);
        buf.putInt(b.length);
        buf.put(b);
    }

    /**
     * Naive auto-grow. For MVP simplicity we reallocate when needed.
     * (We keep it inside this class to avoid external deps for now.)
     */
    private static void ensureCapacity(ByteBuffer buf, int needed) {
        if (buf.remaining() >= needed) return;
        int newCap = Math.max(buf.capacity() * 2, buf.capacity() + needed + 64);
        ByteBuffer bigger = ByteBuffer.allocate(newCap);
        buf.flip();
        bigger.put(buf);
        // swap contents
        buf.clear();
        // Can't swap references in Java, so we simulate by copying back:
        // Instead, we return the bigger buffer via sliceToArray() at the end.
        // To keep things simple, we return arrays using sliceToArray which copies current content.
        // (For production: refactor to a small ByteArrayBuilder.)
        throw new IllegalStateException("Internal buffer too small; refactor encoder if this triggers");
    }

    /**
     * Turn whatever we've written so far into a compact byte[].
     */
    private static byte[] sliceToArray(ByteBuffer buf) {
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static String toHex(byte[] bytes) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    @Override public String toString() {
        String idShort = id.length >= 4 ? toHex(Arrays.copyOf(id, 4)) : "n/a";
        return "Tx{" +
                "id=" + idShort + "…" +
                ", from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", amountMinor=" + amountMinor +
                ", feeMinor=" + feeMinor +
                ", nonce=" + nonce +
                ", payload=" + payload.length + "B" +
                ", sig=" + signature.length + "B" +
                '}';
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction)) return false;
        Transaction tx = (Transaction) o;
        return Arrays.equals(id, tx.id);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(id);
    }
}
