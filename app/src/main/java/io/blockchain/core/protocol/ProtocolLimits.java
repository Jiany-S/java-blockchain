package io.blockchain.core.protocol;

public final class ProtocolLimits {
    private ProtocolLimits(){}

    public static final int MAX_PAYLOAD_BYTES = 8 * 1024;
    public static final int MAX_SIGNATURE_BYTES = 64;      // Ed25519 typical
    public static final int MAX_ADDRESS_LEN = 128;         // sanity cap
    public static final int MIN_ADDRESS_LEN = 8;
    public static final int MAX_TXS_PER_BLOCK = 1_000_000;
}
