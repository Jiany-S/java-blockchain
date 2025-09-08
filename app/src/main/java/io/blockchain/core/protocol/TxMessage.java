package io.blockchain.core.protocol;

import io.blockchain.core.protocol.Transaction;

import java.nio.ByteBuffer;

public final class TxMessage {
    public final Transaction tx;
    public TxMessage(Transaction tx){ this.tx = tx; }

    public byte[] serialize() {
        byte[] b = tx.serialize();
        ByteBuffer buf = ByteBuffer.allocate(4 + b.length);
        buf.putInt(b.length);
        buf.put(b);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    public static TxMessage fromBytes(byte[] bytes){
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int len = buf.getInt();
        byte[] txb = new byte[len];
        buf.get(txb);
        return new TxMessage(io.blockchain.core.protocol.TransactionCodec.fromBytes(txb));
        }
}
