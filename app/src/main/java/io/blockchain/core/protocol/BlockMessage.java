package io.blockchain.core.protocol;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockCodec;

import java.nio.ByteBuffer;

public final class BlockMessage {
    public final Block block;
    public BlockMessage(Block block){ this.block = block; }

    public byte[] serialize(){
        byte[] b = block.serialize();
        ByteBuffer buf = ByteBuffer.allocate(4 + b.length);
        buf.putInt(b.length);
        buf.put(b);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    public static BlockMessage fromBytes(byte[] bytes){
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int len = buf.getInt();
        byte[] bb = new byte[len];
        buf.get(bb);
        return new BlockMessage(BlockCodec.fromBytes(bb));
    }
}
