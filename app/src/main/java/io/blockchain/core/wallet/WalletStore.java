package io.blockchain.core.wallet;

import java.security.*;
import java.util.HashMap;
import java.util.Map;

public final class WalletStore {
    private final Map<String, Wallet> wallets = new HashMap<>();

    public Wallet createWallet(String alias) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair kp = kpg.generateKeyPair();
        Wallet wallet = new Wallet(kp);
        wallets.put(alias, wallet);
        return wallet;
    }

    public Wallet getWallet(String alias) {
        return wallets.get(alias);
    }

    public PublicKey getPublic(String alias) {
        Wallet w = wallets.get(alias);
        return w != null ? w.getPublicKey() : null;
    }

    public PrivateKey getPrivate(String alias) {
        Wallet w = wallets.get(alias);
        return w != null ? w.getPrivateKey() : null;
    }

    public String getAddress(String alias) {
        Wallet w = wallets.get(alias);
        return w != null ? w.getAddress() : null;
    }
}