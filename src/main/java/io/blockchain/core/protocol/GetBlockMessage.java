package io.blockchain.core.protocol.messages;

import java.nio.ByteBuffer;

public final class GetBlockMessage {
    public final byte[] hash;

    public GetBlockMessage(byte[] hash){
        if (hash == null) throw new IllegalArgumentException("hash null");
        this.hash = hash.clone();
    }

    public byte[] serialize(){
        ByteBuffer buf = ByteBuffer.allocate(4 + hash.length);
        buf.putInt(hash.length);
        buf.put(hash);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    public static GetBlockMessage fromBytes(byte[] bytes){
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int len = buf.getInt();
        byte[] h = new byte[len];
        buf.get(h);
        return new GetBlockMessage(h);
    }
}
