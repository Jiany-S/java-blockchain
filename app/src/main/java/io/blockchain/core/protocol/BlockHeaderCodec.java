package io.blockchain.core.protocol;

import java.nio.ByteBuffer;

/**
 * Small helpers used by Block/BlockCodec for deterministic encoding.
 * We keep these as simple length-prefixed byte[] + raw longs.
 */
public final class BlockHeaderCodec {
    private BlockHeaderCodec() {}

    public static void writeBytes(ByteBuffer buf, byte[] v) {
        if (v == null) {
            buf.putInt(0);
            return;
        }
        buf.putInt(v.length);
        buf.put(v);
    }

    public static byte[] readBytes(ByteBuffer buf) {
        int len = buf.getInt();
        if (len == 0) return new byte[32]; // we use 32 zero bytes as default for roots/parents
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    public static void writeLong(ByteBuffer buf, long v) {
        buf.putLong(v);
    }

    public static long readLong(ByteBuffer buf) {
        return buf.getLong();
    }
}
