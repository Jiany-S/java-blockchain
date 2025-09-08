package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Inventory of known object hashes (e.g., tx or block IDs). */
public final class InvMessage {
    public final List<byte[]> hashes;

    public InvMessage(List<byte[]> hashes){
        this.hashes = hashes != null ? hashes : new ArrayList<byte[]>();
    }

    public byte[] serialize(){
        int size = 4;
        for (byte[] h : hashes) size += 4 + (h == null ? 0 : h.length);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(hashes.size());
        for (byte[] h : hashes){
            int len = h == null ? 0 : h.length;
            buf.putInt(len);
            if (h != null) buf.put(h);
        }
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    public static InvMessage fromBytes(byte[] bytes){
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int n = buf.getInt();
        List<byte[]> hs = new ArrayList<byte[]>(n);
        for (int i=0;i<n;i++){
            int len = buf.getInt();
            byte[] h = new byte[len];
            buf.get(h);
            hs.add(h);
        }
        return new InvMessage(hs);
    }
}
