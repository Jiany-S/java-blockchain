package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class TransactionCodec {
    private TransactionCodec(){}

    public static Transaction fromBytes(byte[] bytes) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes);

            int version = buf.getInt();
            int chainId = buf.getInt();
            String from = readString(buf);
            String to = readString(buf);
            long amountMinor = buf.getLong();
            long feeMinor = buf.getLong();
            long nonce = buf.getLong();
            long timestamp = buf.getLong();
            byte[] payload = readBytes(buf);

            byte[] signature = new byte[0];
            if (buf.hasRemaining()) {
                signature = readBytes(buf);
            }

            return Transaction.builder()
                    .version(version)
                    .chainId(chainId)
                    .from(from)
                    .to(to)
                    .amountMinor(amountMinor)
                    .feeMinor(feeMinor)
                    .nonce(nonce)
                    .timestamp(timestamp)
                    .payload(payload)
                    .signature(signature)
                    .build();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Malformed Transaction bytes", ex);
        }
    }

    private static String readString(ByteBuffer b) {
        byte[] arr = readBytes(b);
        return new String(arr, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer b) {
        if (b.remaining() < 4) return new byte[0];
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) {
            throw new IllegalArgumentException("Bad length: " + len + " (remaining=" + b.remaining() + ")");
        }
        byte[] out = new byte[len];
        b.get(out);
        return out;
    }
}
