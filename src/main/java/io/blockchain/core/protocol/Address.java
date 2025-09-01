package io.blockchain.core.protocol;

public final class Address {
    private Address(){}

    public static boolean isValid(String addr) {
        if (addr == null) return false;
        int len = addr.length();
        if (len < ProtocolLimits.MIN_ADDRESS_LEN || len > ProtocolLimits.MAX_ADDRESS_LEN) return false;
        // simple hex-ish guard; to replace with scheme later
        for (int i = 0; i < len; i++) {
            char c = addr.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '_' || c == '-' || c == ':';
            if (!ok) return false;
        }
        return true;
    }
}
