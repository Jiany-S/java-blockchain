package io.blockchain.core.api;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import io.blockchain.core.node.Node;
import io.blockchain.core.wallet.WalletStore;
import io.blockchain.core.protocol.Transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ApiServer {
    private final Node node;
    private final WalletStore walletStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer httpServer;

    public ApiServer(Node node, WalletStore walletStore, int port) {
        this.node = node;
        this.walletStore = walletStore;
    }

    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        // endpoints
        httpServer.createContext("/balance", new BalanceHandler());
        httpServer.createContext("/submit", new SubmitHandler());
        httpServer.createContext("/chain", new ChainInfoHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("API HTTP server started on port " + port);
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
    }

    // Handlers

    class BalanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            String query = exchange.getRequestURI().getQuery(); // e.g. ?address=alice123456
            String address = null;
            if (query != null && query.startsWith("address=")) {
                address = query.substring("address=".length());
            }
            if (address == null) {
                sendResponse(exchange, 400, "Missing address param");
                return;
            }
            long bal = node.state().getBalance(address);
            ObjectNode resp = mapper.createObjectNode().put("address", address).put("balance", bal);
            sendJson(exchange, 200, resp.toString());
        }
    }

    class SubmitHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            TransactionRequest tr = mapper.readValue(exchange.getRequestBody(), TransactionRequest.class);
            try {
                Transaction tx = Transaction.builder()
                        .from(tr.from)
                        .to(tr.to)
                        .amountMinor(tr.amount)
                        .feeMinor(tr.fee)
                        .nonce(tr.nonce)
                        .signature(tr.signatureBytes)
                        .build();
                node.mempool().add(tx);
                sendJson(exchange, 200, mapper.createObjectNode().put("status", "ok").toString());
            } catch (Exception e) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", e.getMessage()).toString());
            }
        }
    }

    class ChainInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            ObjectNode resp = mapper.createObjectNode();
            resp.put("head", node.chain().getHead().map(ApiServer::bytesToHex).orElse(""));
            resp.put("height", node.chain().getHead()
                    .map(h -> node.chain().getHeight(h).orElse(0L))
                    .orElse(0L));
            sendJson(exchange, 200, resp.toString());
        }
    }

    // DTO
    public static class TransactionRequest {
        public String from;
        public String to;
        public long amount;
        public long fee;
        public long nonce;
        public byte[] signatureBytes;
    }

    // Utils
    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
