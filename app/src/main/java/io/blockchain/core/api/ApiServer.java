package io.blockchain.core.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.blockchain.core.node.Node;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.wallet.Wallet;
import io.blockchain.core.wallet.WalletStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class ApiServer {
    private final Node node;
    private final WalletStore walletStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int port;
    private HttpServer httpServer;

    public ApiServer(Node node, WalletStore walletStore, int port) {
        this.node = node;
        this.walletStore = walletStore;
        this.port = port;
    }

    public void start() throws IOException {
        start(this.port);
    }

    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/balance", new BalanceHandler());
        httpServer.createContext("/submit", new SubmitHandler());
        httpServer.createContext("/chain", new ChainInfoHandler());
        httpServer.createContext("/wallets", new WalletsHandler());
        httpServer.createContext("/wallets/send", new WalletSendHandler());
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
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
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
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
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
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
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

    class WalletsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleList(exchange);
                } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleCreate(exchange);
                } else {
                    sendResponse(exchange, 405, "Method Not Allowed");
                }
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", e.getMessage()).toString());
            } catch (Exception e) {
                sendJson(exchange, 500, mapper.createObjectNode().put("error", e.getMessage()).toString());
            }
        }

        private void handleList(HttpExchange exchange) throws IOException {
            List<WalletStore.WalletInfo> infos = walletStore.listWallets();
            ArrayNode array = mapper.createArrayNode();
            for (WalletStore.WalletInfo info : infos) {
                ObjectNode obj = array.addObject();
                obj.put("alias", info.alias);
                obj.put("address", info.address);
                obj.put("locked", info.locked);
            }
            sendJson(exchange, 200, array.toString());
        }

        private void handleCreate(HttpExchange exchange) throws Exception {
            CreateWalletRequest req = mapper.readValue(exchange.getRequestBody(), CreateWalletRequest.class);
            if (req == null || req.alias == null || req.alias.isBlank()) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", "alias required").toString());
                return;
            }
            char[] pass = toPassword(req.passphrase);
            try {
                Wallet wallet = walletStore.createWallet(req.alias, pass);
                ObjectNode resp = mapper.createObjectNode();
                resp.put("alias", req.alias);
                resp.put("address", wallet.getAddress());
                resp.put("locked", walletStore.isLocked(req.alias));
                sendJson(exchange, 201, resp.toString());
            } finally {
                if (pass != null) {
                    Arrays.fill(pass, '\0');
                }
            }
        }
    }

    class WalletSendHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            SendTransactionRequest req;
            try {
                req = mapper.readValue(exchange.getRequestBody(), SendTransactionRequest.class);
            } catch (Exception e) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", "invalid json").toString());
                return;
            }
            if (req == null || req.fromAlias == null || req.fromAlias.isBlank() || req.to == null || req.to.isBlank()) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", "fromAlias and to required").toString());
                return;
            }
            if (req.amountMinor <= 0 || req.feeMinor < 0) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", "invalid amount or fee").toString());
                return;
            }

            char[] pass = toPassword(req.passphrase);
            Wallet wallet = null;
            try {
                if (pass != null) {
                    wallet = walletStore.getWallet(req.fromAlias, pass);
                } else {
                    wallet = walletStore.getWallet(req.fromAlias);
                }
            } catch (IllegalStateException e) {
                sendJson(exchange, 403, mapper.createObjectNode().put("error", e.getMessage()).toString());
                return;
            } catch (Exception e) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", e.getMessage()).toString());
                return;
            } finally {
                if (pass != null) {
                    Arrays.fill(pass, '\0');
                }
            }

            if (wallet == null) {
                sendJson(exchange, 404, mapper.createObjectNode().put("error", "wallet not found").toString());
                return;
            }

            byte[] payload;
            try {
                payload = parseHex(req.payloadHex);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, mapper.createObjectNode().put("error", e.getMessage()).toString());
                return;
            }

            long nonce = node.state().getNonce(wallet.getAddress());
            long timestamp = System.currentTimeMillis();
            Transaction unsignedTx = Transaction.builder()
                    .from(wallet.getAddress())
                    .to(req.to)
                    .amountMinor(req.amountMinor)
                    .feeMinor(req.feeMinor)
                    .nonce(nonce)
                    .timestamp(timestamp)
                    .payload(payload)
                    .build();
            byte[] signature = wallet.sign(unsignedTx.toUnsignedBytes());
            Transaction signedTx = Transaction.builder()
                    .from(unsignedTx.from())
                    .to(unsignedTx.to())
                    .amountMinor(unsignedTx.amountMinor())
                    .feeMinor(unsignedTx.feeMinor())
                    .nonce(unsignedTx.nonce())
                    .timestamp(unsignedTx.timestamp())
                    .payload(unsignedTx.payload())
                    .signature(signature)
                    .publicKey(wallet.getPublicKey())
                    .build();
            node.mempool().add(signedTx);

            ObjectNode resp = mapper.createObjectNode();
            resp.put("status", "ok");
            resp.put("txId", bytesToHex(signedTx.id()));
            resp.put("nonce", signedTx.nonce());
            resp.put("from", signedTx.from());
            resp.put("to", signedTx.to());
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

    public static class CreateWalletRequest {
        public String alias;
        public String passphrase;
    }

    public static class SendTransactionRequest {
        public String fromAlias;
        public String to;
        public long amountMinor;
        public long feeMinor;
        public String passphrase;
        public String payloadHex;
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

    private static char[] toPassword(String value) {
        return value != null && !value.isEmpty() ? value.toCharArray() : null;
    }

    private static byte[] parseHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return new byte[0];
        }
        String normalized = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (normalized.length() % 2 != 0) {
            normalized = "0" + normalized;
        }
        int len = normalized.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(normalized.charAt(i), 16);
            int lo = Character.digit(normalized.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("payloadHex must be hexadecimal");
            }
            out[i / 2] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}
