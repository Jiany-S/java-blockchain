package io.blockchain.core.wallet;

import io.blockchain.core.protocol.SignatureUtil;

import java.security.*;

public class Wallet {
    private final KeyPair keyPair;
    private final String address;

    public Wallet(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.address = SignatureUtil.deriveAddress(keyPair.getPublic());
    }

    public String getAddress() {
        return address;
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public byte[] sign(byte[] data) {
        return SignatureUtil.sign(data, getPrivateKey());
    }

    public boolean verify(byte[] data, byte[] signature) {
        return SignatureUtil.verify(data, signature, getPublicKey());
    }

    public static boolean verifyWithKey(byte[] data, byte[] signature, PublicKey pubKey) {
        try {
            return SignatureUtil.verify(data, signature, pubKey);
        } catch (Exception e) {
            return false;
        }
    }
}
