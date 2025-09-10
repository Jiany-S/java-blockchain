package io.blockchain.core.protocol;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.*;

public final class SignatureUtil {
    static { Security.addProvider(new BouncyCastleProvider()); }

    public static byte[] sign(byte[] data, PrivateKey pk) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA", "BC");
        s.initSign(pk);
        s.update(data);
        return s.sign();
    }

    public static boolean verify(byte[] data, byte[] sig, PublicKey pub) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA", "BC");
        s.initVerify(pub);
        s.update(data);
        return s.verify(sig);
    }
}
