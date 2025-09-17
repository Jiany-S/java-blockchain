package io.blockchain.core.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.blockchain.core.metrics.HttpMetrics;
import io.blockchain.core.node.Node;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.wallet.Wallet;
import io.blockchain.core.wallet.WalletStore;
import io.blockchain.core.wallet.WalletStore.WalletInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ApiServer {
    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final byte[] OPENAPI_SPEC = """
{
  "openapi": "3.0.3",
  "info": {
    "title": "Java Blockchain REST API",
    "version": "1.0.0"
  },
  "paths": {
    "/balance": {
      "get": {
        "summary": "Fetch balance and nonce for an address",
        "parameters": [
          {
            "name": "address",
            "in": "query",
            "required": true,
            "schema": { "type": "string" }
          }
        ],
        "responses": {
          "200": { "description": "Balance information" },
          "400": { "description": "Missing or invalid parameters" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/submit": {
      "post": {
        "summary": "Submit a signed transaction to the mempool",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/SubmitTransaction" }
            }
          }
        },
        "responses": {
          "200": { "description": "Transaction accepted" },
          "400": { "description": "Rejected transaction" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/chain": {
      "get": {
        "summary": "Retrieve chain head and height",
        "responses": {
          "200": { "description": "Chain information" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/wallets": {
      "get": {
        "summary": "List known wallets",
        "responses": { "200": { "description": "Wallet list" }, "401": { "description": "Auth required" } }
      },
      "post": {
        "summary": "Create a new wallet",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/CreateWallet" }
            }
          }
        },
        "responses": {
          "201": { "description": "Wallet created" },
          "400": { "description": "Validation error" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/wallets/send": {
      "post": {
        "summary": "Create, sign, and submit a transaction from a stored wallet",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/WalletSend" }
            }
          }
        },
        "responses": {
          "200": { "description": "Transaction accepted" },
          "400": { "description": "Validation error" },
          "401": { "description": "Auth required" },
          "404": { "description": "Wallet not found" }
        }
      }
    },
    "/openapi.json": {
      "get": {
        "summary": "OpenAPI description of this REST API",
        "responses": { "200": { "description": "OpenAPI specification" }, "401": { "description": "Auth required" } }
      }
    }
  },
  "components": {
    "schemas": {
      "SubmitTransaction": {
        "type": "object",
        "required": ["from", "to", "amount", "fee", "nonce", "signatureBytes"],
        "properties": {
          "from": { "type": "string" },
          "to": { "type": "string" },
          "amount": { "type": "integer", "format": "int64" },
          "fee": { "type": "integer", "format": "int64" },
          "nonce": { "type": "integer", "format": "int64" },
          "signatureBytes": { "type": "string", "format": "byte" }
        }
      },
      "CreateWallet": {
        "type": "object",
        "required": ["alias"],
        "properties": {
          "alias": { "type": "string" },
          "passphrase": { "type": "string" }
        }
      },
      "WalletSend": {
        "type": "object",
        "required": ["fromAlias", "to", "amountMinor", "feeMinor"],
        "properties": {
          "fromAlias": { "type": "string" },
          "to": { "type": "string" },
          "amountMinor": { "type": "integer", "format": "int64" },
          "feeMinor": { "type": "integer", "format": "int64" },
          "passphrase": { "type": "string" },
          "payloadHex": { "type": "string" }
        }
      }
    }
  }
}
""".getBytes(StandardCharsets.UTF_8);

    private final Node node;
    private final WalletStore walletStore;
    private final ObjectMapper mapper;
    private final String bindAddress;
    private final int port;
    private final String authToken;
    private HttpServer httpServer;

    public ApiServer(Node node, WalletStore walletStore, String bindAddress, int port, String authToken) {
        this.node = node;
        this.walletStore = walletStore;
        this.bindAddress = (bindAddress == null || bindAddress.isBlank()) ? "127.0.0.1" : bindAddress;
        this.port = port;
        this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void start() throws IOException {
        if (httpServer != null) {
            throw new IllegalStateException("API server already running");
        }
        httpServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        httpServer.createContext("/balance", new BalanceHandler());
        httpServer.createContext("/submit", new SubmitHandler());
        httpServer.createContext("/chain", new ChainInfoHandler());
        httpServer.createContext("/wallets", new WalletsHandler());
        httpServer.createContext("/wallets/send", new WalletSendHandler());
        httpServer.createContext("/openapi.json", new OpenApiHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        LOG.info(() -> "REST API listening on http://" + bindAddress + ':' + port + (authToken != null ? " (auth required)" : ""));
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    private int ensureAuthorized(HttpExchange exchange) throws IOException {
        if (authToken == null) {
            return -1;
        }
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (header != null && header.equals("Bearer " + authToken)) {
                    return -1;
                }
            }
        }
        String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (apiKey != null && apiKey.equals(authToken)) {
            return -1;
        }
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        return sendError(exchange, 401, "unauthorized", "Missing or invalid credentials");
    }

    class BalanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getHttpContext().getPath();
            var sample = HttpMetrics.start(method, path);
            int status = 500;
            try {
                if (!"GET".equalsIgnoreCase(method)) {
                    status = sendError(exchange, 405, "method_not_allowed", "Use GET for this endpoint");
                    return;
                }
                status = ensureAuthorized(exchange);
                if (status != -1) {
                    return;
                }
                String address = queryParam(exchange, "address");
                if (address == null || address.isBlank()) {
                    status = sendError(exchange, 400, "missing_address", "Query parameter 'address' is required");
                    return;
                }
                ObjectNode resp = mapper.createObjectNode()
                        .put("address", address)
                        .put("balance", node.state().getBalance(address))
                        .put("nonce", node.state().getNonce(address));
                status = sendJson(exchange, 200, resp);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Balance handler failed", e);
                status = sendError(exchange, 500, "internal_error", "Unexpected server error");
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    class SubmitHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getHttpContext().getPath();
            var sample = HttpMetrics.start(method, path);
            int status = 500;
            try {
                if (!"POST".equalsIgnoreCase(method)) {
                    status = sendError(exchange, 405, "method_not_allowed", "Use POST for this endpoint");
                    return;
                }
                status = ensureAuthorized(exchange);
                if (status != -1) {
                    return;
                }
                TransactionRequest req;
                try {
                    req = mapper.readValue(exchange.getRequestBody(), TransactionRequest.class);
                } catch (JsonProcessingException e) {
                    status = sendError(exchange, 400, "invalid_json", "Failed to parse transaction request");
                    return;
                }
                try {
                    Transaction tx = Transaction.builder()
                            .from(req.from)
                            .to(req.to)
                            .amountMinor(req.amount)
                            .feeMinor(req.fee)
                            .nonce(req.nonce)
                            .signature(req.signatureBytes)
                            .build();
                    node.mempool().add(tx);
                    status = sendJson(exchange, 200, mapper.createObjectNode().put("status", "ok"));
                } catch (Exception e) {
                    status = sendError(exchange, 400, "invalid_transaction", Optional.ofNullable(e.getMessage()).orElse("Rejected transaction"));
                }
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    class ChainInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getHttpContext().getPath();
            var sample = HttpMetrics.start(method, path);
            int status = 500;
            try {
                if (!"GET".equalsIgnoreCase(method)) {
                    status = sendError(exchange, 405, "method_not_allowed", "Use GET for this endpoint");
                    return;
                }
                status = ensureAuthorized(exchange);
                if (status != -1) {
                    return;
                }
                ObjectNode resp = mapper.createObjectNode();
                resp.put("head", node.chain().getHead().map(ApiServer::bytesToHex).orElse(""));
                resp.put("height", node.chain().getHead()
                        .map(h -> node.chain().getHeight(h).orElse(0L))
                        .orElse(0L));
                status = sendJson(exchange, 200, resp);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Chain info handler failed", e);
                status = sendError(exchange, 500, "internal_error", "Unexpected server error");
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    class WalletsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getHttpContext().getPath();
            var sample = HttpMetrics.start(method, path);
            int status = 500;
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    status = ensureAuthorized(exchange);
                    if (status != -1) {
                        return;
                    }
                    status = handleList(exchange);
                } else if ("POST".equalsIgnoreCase(method)) {
                    status = ensureAuthorized(exchange);
                    if (status != -1) {
                        return;
                    }
                    status = handleCreate(exchange);
                } else {
                    status = sendError(exchange, 405, "method_not_allowed", "Supported methods: GET, POST");
                }
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }

        private int handleList(HttpExchange exchange) throws IOException {
            List<WalletInfo> infos = walletStore.listWallets();
            ArrayNode array = mapper.createArrayNode();
            for (WalletInfo info : infos) {
                array.addObject()
                        .put("alias", info.alias)
                        .put("address", info.address)
                        .put("locked", info.locked);
            }
            return sendJson(exchange, 200, array);
        }

        private int handleCreate(HttpExchange exchange) throws IOException {
            CreateWalletRequest req;
            try {
                req = mapper.readValue(exchange.getRequestBody(), CreateWalletRequest.class);
            } catch (JsonProcessingException e) {
                return sendError(exchange, 400, "invalid_json", "Failed to parse create wallet request");
            }
            if (req == null || req.alias == null || req.alias.isBlank()) {
                return sendError(exchange, 400, "invalid_alias", "Field 'alias' is required");
            }
            char[] pass = toPassword(req.passphrase);
            try {
                Wallet wallet = walletStore.createWallet(req.alias, pass);
                ObjectNode resp = mapper.createObjectNode()
                        .put("alias", req.alias)
                        .put("address", wallet.getAddress())
                        .put("locked", walletStore.isLocked(req.alias));
                return sendJson(exchange, 201, resp);
            } catch (Exception e) {
                return sendError(exchange, 400, "wallet_creation_failed", Optional.ofNullable(e.getMessage()).orElse("Unable to create wallet"));
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
            String method = exchange.getRequestMethod();
            String path = exchange.getHttpContext().getPath();
            var sample = HttpMetrics.start(method, path);
            int status = 500;
            try {
                if (!"POST".equalsIgnoreCase(method)) {
                    status = sendError(exchange, 405, "method_not_allowed", "Use POST for this endpoint");
                    return;
                }
                status = ensureAuthorized(exchange);
                if (status != -1) {
                    return;
                }
                SendTransactionRequest req;
                try {
                    req = mapper.readValue(exchange.getRequestBody(), SendTransactionRequest.class);
                } catch (JsonProcessingException e) {
                    status = sendError(exchange, 400, "invalid_json", "Failed to parse send transaction request");
                    return;
                }
                if (req == null || req.fromAlias == null || req.fromAlias.isBlank() || req.to == null || req.to.isBlank()) {
                    status = sendError(exchange, 400, "missing_fields", "Fields 'fromAlias' and 'to' are required");
                    return;
                }
                if (req.amountMinor <= 0 || req.feeMinor < 0) {
                    status = sendError(exchange, 400, "invalid_amount", "Amount must be > 0 and fee >= 0");
                    return;
                }

                char[] pass = toPassword(req.passphrase);
                Wallet wallet;
                try {
                    wallet = pass != null ? walletStore.getWallet(req.fromAlias, pass) : walletStore.getWallet(req.fromAlias);
                } catch (IllegalStateException e) {
                    status = sendError(exchange, 403, "wallet_locked", e.getMessage());
                    return;
                } catch (Exception e) {
                    status = sendError(exchange, 400, "wallet_error", Optional.ofNullable(e.getMessage()).orElse("Unable to unlock wallet"));
                    return;
                } finally {
                    if (pass != null) {
                        Arrays.fill(pass, '\0');
                    }
                }

                if (wallet == null) {
                    status = sendError(exchange, 404, "wallet_not_found", "No wallet registered for alias " + req.fromAlias);
                    return;
                }

                byte[] payload;
                try {
                    payload = parseHex(req.payloadHex);
                } catch (IllegalArgumentException e) {
                    status = sendError(exchange, 400, "invalid_payload", e.getMessage());
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
                status = sendJson(exchange, 200, resp);
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    class OpenApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getHttpContext().getPath();
            var sample = HttpMetrics.start(method, path);
            int status = 500;
            try {
                if (!"GET".equalsIgnoreCase(method)) {
                    status = sendError(exchange, 405, "method_not_allowed", "Use GET for this endpoint");
                    return;
                }
                status = ensureAuthorized(exchange);
                if (status != -1) {
                    return;
                }
                status = sendJson(exchange, 200, OPENAPI_SPEC);
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

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

    private int sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload;
        if (body instanceof byte[] bytes) {
            payload = bytes;
        } else if (body instanceof String str) {
            payload = str.getBytes(StandardCharsets.UTF_8);
        } else {
            payload = mapper.writeValueAsBytes(body);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
        return status;
    }

    private int sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        return sendJson(exchange, status, node);
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

    private String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            if (key.equals(k)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
