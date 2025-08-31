package io.blockchain.core.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashes {
    private Hashes(){}
    static byte[] sha256(byte[] in){
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
