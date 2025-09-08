package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class BlockCodec {
    private BlockCodec(){}

    public static Block fromBytes(byte[] bytes) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes);

            // Parse header inline using the same order as BlockHeader#serialize
            byte[] parent = BlockHeaderCodec.readBytes(buf);
            byte[] merkle = BlockHeaderCodec.readBytes(buf);
            long height = buf.getLong();
            long ts = buf.getLong();
            long diffOrSlot = buf.getLong();
            long nonce = buf.getLong();
            BlockHeader header = new BlockHeader(parent, merkle, height, ts, diffOrSlot, nonce);

            int count = buf.getInt();
            if (count < 0 || count > ProtocolLimits.MAX_TXS_PER_BLOCK) {
                throw new IllegalArgumentException("bad tx count: " + count);
            }

            List<Transaction> txs = new ArrayList<Transaction>(count);
            for (int i = 0; i < count; i++) {
                int len = buf.getInt();
                if (len < 0 || len > 16_000_000) throw new IllegalArgumentException("bad tx length: "+len);
                byte[] txBytes = new byte[len];
                buf.get(txBytes);
                txs.add(TransactionCodec.fromBytes(txBytes));
            }
            return new Block(header, txs);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Malformed Block bytes", ex);
        }
    }
}
