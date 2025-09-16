package io.blockchain.core.wallet;

import io.blockchain.core.protocol.SignatureUtil;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WalletStore {
    private static final String PRIV_EXT = ".key";
    private static final String PUB_EXT = ".pub";
    private static final int KEY_SIZE = 256;
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 65_536;
    private static final String[] PBE_ALGORITHMS = {
            "PBEWithHmacSHA256AndAES_256",
            "PBEWithHmacSHA256AndAES_128"
    };

    private final Map<String, WalletRecord> wallets = new HashMap<>();
    private final Path directory;
    private final SecureRandom random = new SecureRandom();

    public WalletStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        loadExisting();
    }

    public WalletStore() {
        this(Path.of("./wallets"));
    }

    private void loadExisting() {
        try {
            Files.createDirectories(directory);
            try (var stream = Files.list(directory)) {
                stream.filter(path -> path.getFileName().toString().endsWith(PRIV_EXT))
                        .forEach(this::loadWalletFromDisk);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize wallet store", e);
        }
    }

    private void loadWalletFromDisk(Path privatePath) {
        String file = privatePath.getFileName().toString();
        if (!file.endsWith(PRIV_EXT)) {
            return;
        }
        String alias = file.substring(0, file.length() - PRIV_EXT.length());
        Path publicPath = directory.resolve(alias + PUB_EXT);
        if (!Files.exists(publicPath)) {
            return;
        }
        try {
            String privPem = Files.readString(privatePath, StandardCharsets.US_ASCII);
            String pubPem = Files.readString(publicPath, StandardCharsets.US_ASCII);
            boolean encrypted = privPem.contains("ENCRYPTED PRIVATE KEY");
            PublicKey publicKey = decodePublicKey(pubPem);
            String address = SignatureUtil.deriveAddress(publicKey);
            Wallet wallet = null;
            if (!encrypted) {
                PrivateKey privateKey = decodePrivateKey(privPem, null);
                wallet = new Wallet(new KeyPair(publicKey, privateKey));
            }
            wallets.put(alias, new WalletRecord(alias, publicKey, address, privatePath, publicPath, encrypted, wallet));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load wallet '" + alias + "'", e);
        }
    }

    public synchronized Wallet createWallet(String alias) throws Exception {
        return createWallet(alias, null);
    }

    public synchronized Wallet createWallet(String alias, char[] passphrase) throws Exception {
        Objects.requireNonNull(alias, "alias");
        validateAlias(alias);
        if (wallets.containsKey(alias)) {
            throw new IllegalArgumentException("Wallet alias already exists: " + alias);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(KEY_SIZE);
        KeyPair keyPair = generator.generateKeyPair();

        Files.createDirectories(directory);
        Path privPath = directory.resolve(alias + PRIV_EXT);
        Path pubPath = directory.resolve(alias + PUB_EXT);

        String privatePem = encodePrivateKeyPem(keyPair.getPrivate(), passphrase);
        String publicPem = encodePublicKeyPem(keyPair.getPublic());

        Files.writeString(privPath, privatePem, StandardCharsets.US_ASCII);
        Files.writeString(pubPath, publicPem, StandardCharsets.US_ASCII);

        Wallet wallet = new Wallet(keyPair);
        boolean encrypted = passphrase != null && passphrase.length > 0;
        WalletRecord record = new WalletRecord(alias, keyPair.getPublic(), wallet.getAddress(), privPath, pubPath, encrypted, encrypted ? null : wallet);
        wallets.put(alias, record);
        return wallet;
    }

    public synchronized Wallet unlockWallet(String alias, char[] passphrase) throws Exception {
        WalletRecord record = requireRecord(alias);
        if (!record.encrypted) {
            return ensureLoaded(record);
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Missing passphrase for encrypted wallet: " + alias);
        }
        Wallet wallet;
        try {
            wallet = loadWallet(record, passphrase);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
        record.wallet = wallet;
        return wallet;
    }

    public synchronized Wallet getWallet(String alias) {
        WalletRecord record = wallets.get(alias);
        if (record == null) {
            return null;
        }
        return ensureLoaded(record);
    }

    public synchronized Wallet getWallet(String alias, char[] passphrase) throws Exception {
        WalletRecord record = wallets.get(alias);
        if (record == null) {
            return null;
        }
        if (record.wallet != null) {
            return record.wallet;
        }
        return unlockWallet(alias, passphrase);
    }

    public synchronized List<WalletInfo> listWallets() {
        List<WalletInfo> out = new ArrayList<>(wallets.size());
        for (WalletRecord record : wallets.values()) {
            out.add(new WalletInfo(record.alias, record.address, record.locked()));
        }
        out.sort(Comparator.comparing(info -> info.alias));
        return out;
    }

    public synchronized WalletInfo info(String alias) {
        WalletRecord record = wallets.get(alias);
        if (record == null) {
            return null;
        }
        return new WalletInfo(record.alias, record.address, record.locked());
    }

    public synchronized PublicKey getPublic(String alias) {
        WalletRecord record = wallets.get(alias);
        return record != null ? record.publicKey : null;
    }

    public synchronized PrivateKey getPrivate(String alias) {
        Wallet wallet = getWallet(alias);
        return wallet != null ? wallet.getPrivateKey() : null;
    }

    public synchronized String getAddress(String alias) {
        WalletRecord record = wallets.get(alias);
        return record != null ? record.address : null;
    }

    public synchronized boolean isLocked(String alias) {
        WalletRecord record = wallets.get(alias);
        return record != null && record.locked();
    }

    private Wallet ensureLoaded(WalletRecord record) {
        if (record.wallet != null) {
            return record.wallet;
        }
        if (record.encrypted) {
            throw new IllegalStateException("Wallet '" + record.alias + "' is encrypted; unlock before use");
        }
        try {
            Wallet wallet = loadWallet(record, null);
            record.wallet = wallet;
            return wallet;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load wallet '" + record.alias + "'", e);
        }
    }

    private Wallet loadWallet(WalletRecord record, char[] passphrase) throws Exception {
        String privPem = Files.readString(record.privatePath, StandardCharsets.US_ASCII);
        PrivateKey privateKey = decodePrivateKey(privPem, passphrase);
        return new Wallet(new KeyPair(record.publicKey, privateKey));
    }

    private WalletRecord requireRecord(String alias) {
        WalletRecord record = wallets.get(alias);
        if (record == null) {
            throw new IllegalArgumentException("Unknown wallet alias: " + alias);
        }
        return record;
    }

    private static void validateAlias(String alias) {
        if (alias.isBlank() || alias.contains("/") || alias.contains("\\") || alias.contains("..")) {
            throw new IllegalArgumentException("Invalid wallet alias: " + alias);
        }
    }

    private String encodePrivateKeyPem(PrivateKey privateKey, char[] passphrase) throws GeneralSecurityException {
        byte[] pkcs8 = privateKey.getEncoded();
        if (passphrase == null || passphrase.length == 0) {
            return toPem("PRIVATE KEY", pkcs8);
        }
        byte[] encrypted = encryptPkcs8(pkcs8, passphrase);
        return toPem("ENCRYPTED PRIVATE KEY", encrypted);
    }

    private String encodePublicKeyPem(PublicKey publicKey) {
        return toPem("PUBLIC KEY", publicKey.getEncoded());
    }

    private PrivateKey decodePrivateKey(String pem, char[] passphrase) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("EC");
        if (pem.contains("ENCRYPTED PRIVATE KEY")) {
            if (passphrase == null || passphrase.length == 0) {
                throw new IllegalArgumentException("Passphrase required for encrypted key");
            }
            byte[] encoded = fromPem("ENCRYPTED PRIVATE KEY", pem);
            EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(encoded);
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(info.getAlgName());
            PBEKeySpec keySpec = new PBEKeySpec(passphrase);
            SecretKey secretKey = keyFactory.generateSecret(keySpec);
            Cipher cipher = Cipher.getInstance(info.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE, secretKey, info.getAlgParameters());
            byte[] decrypted = cipher.doFinal(info.getEncryptedData());
            return factory.generatePrivate(new PKCS8EncodedKeySpec(decrypted));
        }
        byte[] encoded = fromPem("PRIVATE KEY", pem);
        return factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private PublicKey decodePublicKey(String pem) throws Exception {
        byte[] encoded = fromPem("PUBLIC KEY", pem);
        KeyFactory factory = KeyFactory.getInstance("EC");
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    private byte[] encryptPkcs8(byte[] pkcs8, char[] passphrase) throws GeneralSecurityException {
        GeneralSecurityException last = null;
        for (String algorithm : PBE_ALGORITHMS) {
            try {
                byte[] salt = new byte[SALT_BYTES];
                random.nextBytes(salt);
                PBEParameterSpec parameterSpec = new PBEParameterSpec(salt, ITERATIONS);
                PBEKeySpec keySpec = new PBEKeySpec(passphrase);
                SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(algorithm);
                SecretKey secretKey = keyFactory.generateSecret(keySpec);
                Cipher cipher = Cipher.getInstance(algorithm);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
                byte[] encrypted = cipher.doFinal(pkcs8);
                EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(cipher.getParameters(), encrypted);
                try {
                    return info.getEncoded();
                } catch (IOException ioException) {
                    throw new GeneralSecurityException("Failed to encode encrypted private key", ioException);
                }
            } catch (GeneralSecurityException ex) {
                last = ex;
            }
        }
        throw last != null ? last : new GeneralSecurityException("No supported PBE algorithms available");
    }

    private static byte[] fromPem(String type, String pem) {
        String header = "-----BEGIN " + type + "-----";
        String footer = "-----END " + type + "-----";
        String normalized = pem.replace("\r", "").trim();
        int start = normalized.indexOf(header);
        int end = normalized.indexOf(footer);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Invalid PEM encoding for " + type);
        }
        start += header.length();
        String base64 = normalized.substring(start, end).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static String toPem(String type, byte[] der) {
        StringBuilder sb = new StringBuilder();
        String newline = System.lineSeparator();
        sb.append("-----BEGIN ").append(type).append("-----").append(newline);
        String base64 = Base64.getEncoder().encodeToString(der);
        for (int i = 0; i < base64.length(); i += 64) {
            int end = Math.min(i + 64, base64.length());
            sb.append(base64, i, end).append(newline);
        }
        sb.append("-----END ").append(type).append("-----").append(newline);
        return sb.toString();
    }

    public static final class WalletInfo {
        public final String alias;
        public final String address;
        public final boolean locked;

        public WalletInfo(String alias, String address, boolean locked) {
            this.alias = alias;
            this.address = address;
            this.locked = locked;
        }
    }

    private static final class WalletRecord {
        final String alias;
        final PublicKey publicKey;
        final String address;
        final Path privatePath;
        final Path publicPath;
        final boolean encrypted;
        Wallet wallet;

        WalletRecord(String alias,
                     PublicKey publicKey,
                     String address,
                     Path privatePath,
                     Path publicPath,
                     boolean encrypted,
                     Wallet wallet) {
            this.alias = alias;
            this.publicKey = publicKey;
            this.address = address;
            this.privatePath = privatePath;
            this.publicPath = publicPath;
            this.encrypted = encrypted;
            this.wallet = wallet;
        }

        boolean locked() {
            return encrypted && wallet == null;
        }
    }
}



