package io.blockchain.core.p2p;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record P2pMessage(String type, Map<String, Object> payload) {
    public P2pMessage {
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Collections.emptyMap() : Collections.unmodifiableMap(payload);
    }

    public static P2pMessage handshake(String nodeId) {
        return new P2pMessage("handshake", Map.of("nodeId", nodeId));
    }
}
