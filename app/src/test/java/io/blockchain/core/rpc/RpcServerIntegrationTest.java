package io.blockchain.core.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.blockchain.core.node.Node;
import io.blockchain.core.node.NodeConfig;
import io.blockchain.core.wallet.Wallet;
import io.blockchain.core.wallet.WalletStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RpcServerIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private RpcServer server;
    private Node node;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (node != null) {
            node.close();
        }
    }

    @Test
    void txEndpointAcceptsValidTransaction() throws Exception {
        int port = freePort();
        WalletStore store = new WalletStore(tempDir);
        Wallet alice = store.createWallet("alice");
        Wallet bob = store.createWallet("bob");

        node = Node.inMemory(NodeConfig.defaultLocal());
        node.start();
        node.state().credit(alice.getAddress(), 1_000);

        server = new RpcServer(node, store, "127.0.0.1", port, "rpc-secret");
        server.start();

        long nonce = node.state().getNonce(alice.getAddress());
        String payload = mapper.createObjectNode()
                .put("from", alice.getAddress())
                .put("to", bob.getAddress())
                .put("amountMinor", 50)
                .put("feeMinor", 2)
                .put("nonce", nonce)
                .put("payloadHex", "")
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + port + "/tx"))
                .header("Authorization", "Bearer rpc-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":true"));
    }

    @Test
    void txEndpointRejectsInvalidAmount() throws Exception {
        int port = freePort();
        WalletStore store = new WalletStore(tempDir);
        Wallet alice = store.createWallet("alice");
        Wallet bob = store.createWallet("bob");

        node = Node.inMemory(NodeConfig.defaultLocal());
        node.start();
        node.state().credit(alice.getAddress(), 100);

        server = new RpcServer(node, store, "127.0.0.1", port, "rpc-secret");
        server.start();

        long nonce = node.state().getNonce(alice.getAddress());
        String payload = mapper.createObjectNode()
                .put("from", alice.getAddress())
                .put("to", bob.getAddress())
                .put("amountMinor", -5)
                .put("feeMinor", 1)
                .put("nonce", nonce)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://127.0.0.1:" + port + "/tx"))
                .header("Authorization", "Bearer rpc-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("invalid_amount"));
        assertEquals(0, node.mempool().size());
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
