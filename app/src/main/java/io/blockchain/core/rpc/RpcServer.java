package io.blockchain.core.rpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.blockchain.core.metrics.HttpMetrics;
import io.blockchain.core.metrics.BlockMetrics;
import io.blockchain.core.node.Node;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.wallet.Wallet;
import io.blockchain.core.wallet.WalletStore;
import io.blockchain.core.wallet.WalletStore.WalletInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RpcServer {
    private static final Logger LOG = Logger.getLogger(RpcServer.class.getName());
    private static final byte[] OPENAPI_SPEC = """
{
  "openapi": "3.0.3",
  "info": {
    "title": "Java Blockchain RPC API",
    "version": "1.0.0"
  },
  "paths": {
    "/status": {
      "get": {
        "summary": "Node status and head information",
        "responses": { "200": { "description": "Status response" }, "401": { "description": "Auth required" } }
      }
    },
    "/balance": {
      "get": {
        "summary": "Balance and nonce for an address",
        "parameters": [
          {
            "name": "addr",
            "in": "query",
            "required": true,
            "schema": { "type": "string" }
          }
        ],
        "responses": {
          "200": { "description": "Balance response" },
          "400": { "description": "Missing or invalid parameters" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/tx": {
      "post": {
        "summary": "Submit a transaction body",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/TxRequest" }
            }
          }
        },
        "responses": {
          "200": { "description": "Transaction accepted" },
          "400": { "description": "Invalid transaction" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/wallet/list": {
      "get": {
        "summary": "List wallet aliases and addresses",
        "responses": { "200": { "description": "Wallet list" }, "401": { "description": "Auth required" } }
      }
    },
    "/wallet/create": {
      "post": {
        "summary": "Create a new wallet alias",
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
    "/wallet/send": {
      "post": {
        "summary": "Sign and submit a transaction using a stored wallet",
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
          "404": { "description": "Wallet not found" },
          "401": { "description": "Auth required" }
        }
      }
    },
    "/metrics": {
      "get": {
        "summary": "Prometheus metrics scrape",
        "responses": { "200": { "description": "Metrics in Prometheus exposition format" }, "401": { "description": "Auth required" } }
      }
    },
    "/openapi.json": {
      "get": {
        "summary": "OpenAPI description of this RPC API",
        "responses": { "200": { "description": "OpenAPI specification" }, "401": { "description": "Auth required" } }
      }
    }
  },
  "components": {
    "schemas": {
      "TxRequest": {
        "type": "object",
        "required": ["from", "to", "amountMinor", "feeMinor", "nonce"],
        "properties": {
          "from": { "type": "string" },
          "to": { "type": "string" },
          "amountMinor": { "type": "integer", "format": "int64" },
          "feeMinor": { "type": "integer", "format": "int64" },
          "nonce": { "type": "integer", "format": "int64" },
          "payloadHex": { "type": "string" }
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
    private final String bindAddress;
    private final int port;
    private final String authToken;
    private final ObjectMapper mapper;
    private HttpServer server;

    public RpcServer(Node node, WalletStore walletStore, String bindAddress, int port, String authToken) {
        this.node = node;
        this.walletStore = walletStore;
        this.bindAddress = (bindAddress == null || bindAddress.isBlank()) ? "127.0.0.1" : bindAddress;
        this.port = port;
        this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("RPC server already running");
        }
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/status", new StatusHandler());
        server.createContext("/balance", new BalanceHandler());
        server.createContext("/tx", new TxHandler());
        server.createContext("/wallet/list", new WalletListHandler());
        server.createContext("/wallet/create", new WalletCreateHandler());
        server.createContext("/wallet/send", new WalletSendHandler());
        server.createContext("/metrics", new MetricsHandlerImpl());
        server.createContext("/openapi.json", new OpenApiHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        LOG.info(() -> "RPC server listening on http://" + bindAddress + ':' + port + (authToken != null ? " (auth required)" : ""));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
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

    final class StatusHandler implements HttpHandler {
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
                long height = -1;
                String headHex = "null";
                Optional<byte[]> head = node.chain().getHead();
                if (head.isPresent()) {
                    height = node.chain().getHeight(head.get()).orElse(-1L);
                    headHex = toHex(head.get());
                }
                ObjectNode resp = mapper.createObjectNode();
                resp.put("height", height);
                resp.put("head", headHex);
                resp.put("mempool", node.mempool().size());
                status = sendJson(exchange, 200, resp);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Status handler failed", e);
                status = sendError(exchange, 500, "internal_error", "Unexpected server error");
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    final class BalanceHandler implements HttpHandler {
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
                String address = queryParam(exchange.getRequestURI(), "addr");
                if (address == null || address.isBlank()) {
                    status = sendError(exchange, 400, "missing_addr", "Query parameter 'addr' is required");
                    return;
                }
                ObjectNode resp = mapper.createObjectNode();
                resp.put("address", address);
                resp.put("balance", node.state().getBalance(address));
                resp.put("nonce", node.state().getNonce(address));
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

    final class TxHandler implements HttpHandler {
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
                TxRequest req;
                try {
                    req = mapper.readValue(exchange.getRequestBody(), TxRequest.class);
                } catch (JsonProcessingException e) {
                    status = sendError(exchange, 400, "invalid_json", "Failed to parse transaction request");
                    return;
                }
                if (req == null || req.from == null || req.to == null) {
                    status = sendError(exchange, 400, "missing_fields", "Fields 'from' and 'to' are required");
                    return;
                }
                try {
                    Transaction.Builder builder = Transaction.builder()
                            .from(req.from)
                            .to(req.to)
                            .amountMinor(req.amountMinor)
                            .feeMinor(req.feeMinor)
                            .nonce(req.nonce);
                    if (req.payloadHex != null) {
                        builder.payload(parseHex(req.payloadHex));
                    }
                    Transaction tx = builder.build();
                    node.mempool().add(tx);
                    ObjectNode resp = mapper.createObjectNode()
                            .put("accepted", true)
                            .put("id", toHex(tx.id()));
                    status = sendJson(exchange, 200, resp);
                } catch (Exception e) {
                    status = sendError(exchange, 400, "invalid_transaction", Optional.ofNullable(e.getMessage()).orElse("Rejected transaction"));
                }
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    final class WalletListHandler implements HttpHandler {
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
                List<WalletInfo> infos = walletStore.listWallets();
                ArrayNode array = mapper.createArrayNode();
                for (WalletInfo info : infos) {
                    array.addObject()
                            .put("alias", info.alias)
                            .put("address", info.address)
                            .put("locked", info.locked);
                }
                ObjectNode resp = mapper.createObjectNode().set("wallets", array);
                status = sendJson(exchange, 200, resp);
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    final class WalletCreateHandler implements HttpHandler {
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
                WalletCreateRequest req;
                try {
                    req = mapper.readValue(exchange.getRequestBody(), WalletCreateRequest.class);
                } catch (JsonProcessingException e) {
                    status = sendError(exchange, 400, "invalid_json", "Failed to parse create wallet request");
                    return;
                }
                if (req == null || req.alias == null || req.alias.isBlank()) {
                    status = sendError(exchange, 400, "invalid_alias", "Field 'alias' is required");
                    return;
                }
                char[] pass = toPassword(req.passphrase);
                try {
                    walletStore.createWallet(req.alias, pass);
                    WalletInfo info = walletStore.info(req.alias);
                    ObjectNode resp = mapper.createObjectNode()
                            .put("alias", info.alias)
                            .put("address", info.address)
                            .put("locked", info.locked);
                    status = sendJson(exchange, 201, resp);
                } catch (Exception e) {
                    status = sendError(exchange, 400, "wallet_creation_failed", Optional.ofNullable(e.getMessage()).orElse("Unable to create wallet"));
                } finally {
                    if (pass != null) {
                        Arrays.fill(pass, '\0');
                    }
                }
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    final class WalletSendHandler implements HttpHandler {
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
                WalletSendRequest req;
                try {
                    req = mapper.readValue(exchange.getRequestBody(), WalletSendRequest.class);
                } catch (JsonProcessingException e) {
                    status = sendError(exchange, 400, "invalid_json", "Failed to parse wallet send request");
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

                ObjectNode resp = mapper.createObjectNode()
                        .put("accepted", true)
                        .put("txId", toHex(signedTx.id()))
                        .put("nonce", signedTx.nonce());
                status = sendJson(exchange, 200, resp);
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    final class MetricsHandlerImpl implements HttpHandler {
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
                String metrics = BlockMetrics.scrapeMetrics();
                byte[] payload = metrics.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
                status = 200;
            } finally {
                HttpMetrics.stop(sample, method, path, status);
                exchange.close();
            }
        }
    }

    final class OpenApiHandler implements HttpHandler {
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

    private String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String toHex(byte[] bytes) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0f];
        }
        return new String(out);
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

    private static char[] toPassword(String value) {
        return value != null && !value.isEmpty() ? value.toCharArray() : null;
    }

    private static class TxRequest {
        public String from;
        public String to;
        public long amountMinor;
        public long feeMinor;
        public long nonce;
        public String payloadHex;
    }

    private static class WalletCreateRequest {
        public String alias;
        public String passphrase;
    }

    private static class WalletSendRequest {
        public String fromAlias;
        public String to;
        public long amountMinor;
        public long feeMinor;
        public String passphrase;
        public String payloadHex;
    }
}
