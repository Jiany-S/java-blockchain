package io.blockchain.core.api;

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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApiServerAuthTest {

    @TempDir
    Path tempDir;

    private ApiServer server;
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
    void balanceEndpointRequiresAuth() throws Exception {
        int port = freePort();
        WalletStore store = new WalletStore(tempDir);
        Wallet wallet = store.createWallet("alice");

        node = Node.inMemory(NodeConfig.defaultLocal());
        node.start();
        node.state().credit(wallet.getAddress(), 1234);

        server = new ApiServer(node, store, "127.0.0.1", port, "secret-token");
        server.start();

        HttpClient client = HttpClient.newHttpClient();

        URI uri = new URI("http://127.0.0.1:" + port + "/balance?address=" + wallet.getAddress());
        HttpResponse<String> unauthorized = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").isPresent());

        HttpRequest authorizedRequest = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer secret-token")
                .GET()
                .build();
        HttpResponse<String> authorized = client.send(authorizedRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, authorized.statusCode());
        assertTrue(authorized.body().contains("\"balance\":1234"));
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

}
