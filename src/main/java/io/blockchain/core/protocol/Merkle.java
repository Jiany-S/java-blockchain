package io.blockchain.core.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple Merkle tree helper over byte[] leaves.
 * - If there are no leaves, root = 32 zero bytes.
 * - If odd count at a level, duplicate the last (Bitcoin-style) for simplicity.
 */
public final class Merkle {
    private Merkle(){}

    public static byte[] rootOf(List<byte[]> leaves) {
        if (leaves == null || leaves.isEmpty()) return new byte[32];
        List<byte[]> level = new ArrayList<>(leaves);
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size()+1)/2);
            for (int i=0; i<level.size(); i+=2) {
                byte[] left = level.get(i);
                byte[] right = (i+1 < level.size()) ? level.get(i+1) : left;
                next.add(Hashes.sha256(concat(left, right)));
            }
            level = next;
        }
        return level.get(0);
    }

    public static List<byte[]> proofFor(List<byte[]> leaves, int index) {
        // Minimal for now: you can add proofs later if you need them.
        throw new UnsupportedOperationException("Merkle proofs not implemented yet");
    }

    public static boolean verify(byte[] root, byte[] leaf, List<byte[]> proof) {
        throw new UnsupportedOperationException("Merkle verify not implemented yet");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
