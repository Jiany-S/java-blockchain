package io.blockchain.core.protocol;

import java.util.Arrays;

public final class Hash {
    public static final int LENGTH = 32;
    private final byte[] bytes;

    public Hash(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) {
            throw new IllegalArgumentException("Hash must be 32 bytes");
        }
        this.bytes = bytes.clone();
    }
    public byte[] bytes() { return bytes.clone(); }
    public String hex() { return toHex(bytes); }

    private static String toHex(byte[] b){
        final char[] HEX="0123456789abcdef".toCharArray();
        char[] out=new char[b.length*2];
        for(int i=0,j=0;i<b.length;i++){int v=b[i]&0xff;out[j++]=HEX[v>>>4];out[j++]=HEX[v&0x0f];}
        return new String(out);
    }

    @Override public boolean equals(Object o){ return o instanceof Hash && Arrays.equals(bytes, ((Hash)o).bytes); }
    @Override public int hashCode(){ return Arrays.hashCode(bytes); }
    @Override public String toString(){ return "Hash("+hex().substring(0,8)+"…)"; }
}
