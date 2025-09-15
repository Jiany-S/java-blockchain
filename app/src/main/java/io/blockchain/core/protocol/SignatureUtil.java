package io.blockchain.core.protocol;

import java.security.*;

public final class SignatureUtil {
    private SignatureUtil() {}

    public static byte[] sign(byte[] data, PrivateKey priv) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(priv);
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new RuntimeException("Signing failed", e);
        }
    }

    public static boolean verify(byte[] data, byte[] signature, PublicKey pub) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pub);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static String deriveAddress(PublicKey pub) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(pub.getEncoded());
            // hex string, first 40 chars
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Address derivation failed", e);
        }
    }
}
