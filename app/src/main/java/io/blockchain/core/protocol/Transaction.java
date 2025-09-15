package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal, deterministic transaction model for the MVP chain.
 */
public final class Transaction {

    private final int version;
    private final int chainId;

    private final String from;
    private final String to;
    private final long amountMinor;
    private final long feeMinor;

    private final long nonce;
    private final long timestamp;

    private final byte[] payload;
    private final byte[] signature;

    private final PublicKey publicKey;
    private final byte[] id;

    private Transaction(int version,
                        int chainId,
                        String from,
                        String to,
                        long amountMinor,
                        long feeMinor,
                        long nonce,
                        long timestamp,
                        byte[] payload,
                        byte[] signature,
                        PublicKey publicKey) {
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
        this.publicKey = publicKey;
        this.id = sha256(toUnsignedBytes());
        basicValidate();
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
        private PublicKey publicKey;

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
        public Builder publicKey(PublicKey pk) { this.publicKey = pk; return this; }

        public Transaction build() {
            return new Transaction(version, chainId, from, to, amountMinor, feeMinor,
                                   nonce, timestamp, payload, signature, publicKey);
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
    public PublicKey publicKey() { return publicKey; }
    public byte[] id() { return id.clone(); }

    // -------------------- core methods --------------------
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(estimateSize(true));
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
        putBytes(buf, publicKey != null ? publicKey.getEncoded() : new byte[0]);
        return sliceToArray(buf);
    }

    public byte[] toUnsignedBytes() {
        ByteBuffer buf = ByteBuffer.allocate(estimateSize(false));
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

    public void basicValidate() {
        if (version != 1) throw new IllegalArgumentException("Unsupported version: " + version);
        if (chainId <= 0) throw new IllegalArgumentException("Invalid chainId");
        if (from == null || from.isBlank()) throw new IllegalArgumentException("Missing from");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("Missing to");
        if (Objects.equals(from, to)) throw new IllegalArgumentException("from == to");
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be > 0");
        if (feeMinor < 0) throw new IllegalArgumentException("fee must be >= 0");
        if (nonce < 0) throw new IllegalArgumentException("nonce must be >= 0");
        if (timestamp <= 0) throw new IllegalArgumentException("timestamp must be > 0");
    }

    // -------------------- helpers --------------------
    private int estimateSize(boolean includeSig) {
        int size = 0;
        size += 4 + 4;
        size += 4 + (from == null ? 0 : from.getBytes(StandardCharsets.UTF_8).length);
        size += 4 + (to == null ? 0 : to.getBytes(StandardCharsets.UTF_8).length);
        size += 8 * 4;
        size += 4 + (payload == null ? 0 : payload.length);
        if (includeSig) size += 4 + (signature == null ? 0 : signature.length);
        if (includeSig) size += 4 + (publicKey == null ? 0 : publicKey.getEncoded().length);
        return size;
    }

    private static byte[] sha256(byte[] in) {
        try { return MessageDigest.getInstance("SHA-256").digest(in); }
        catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    private static void putInt(ByteBuffer buf, int v){ buf.putInt(v); }
    private static void putLong(ByteBuffer buf, long v){ buf.putLong(v); }
    private static void putStr(ByteBuffer buf, String s){
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        putBytes(buf, b);
    }
    private static void putBytes(ByteBuffer buf, byte[] b){
        buf.putInt(b.length); buf.put(b);
    }
    private static byte[] sliceToArray(ByteBuffer buf){
        buf.flip(); byte[] out = new byte[buf.remaining()]; buf.get(out); return out;
    }
}
