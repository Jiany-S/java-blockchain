package io.blockchain.core.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.blockchain.core.node.Node;
import io.blockchain.core.protocol.Transaction;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Very small HTTP API (no external deps):
 *  - GET  /status
 *  - GET  /balance?addr=<address>
 *  - POST /tx   { "from":"...", "to":"...", "amountMinor":123, "feeMinor":1, "nonce":0 }
 */
public final class RpcServer {
    private static final Logger LOG = Logger.getLogger(RpcServer.class.getName());

    private final Node node;
    private final int port;
    private HttpServer server;

    public RpcServer(Node node, int port) { this.node = node; this.port = port; }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/status", new StatusHandler(node));
        server.createContext("/balance", new BalanceHandler(node));
        server.createContext("/tx", new TxHandler(node));
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/metrics", new MetricsHandler());
        server.start();
    }

    public void stop() { if (server != null) server.stop(0); }

    // ---------------- handlers ----------------

    static final class StatusHandler implements HttpHandler {
        private final Node node;
        StatusHandler(Node node){ this.node = node; }

        public void handle(HttpExchange ex) throws IOException {
            long height = -1;
            String headHex = "null";
            int mempoolSize = node.mempool().size();
            try {
                Optional<byte[]> head = node.chain().getHead();
                if (head.isPresent()) {
                    height = node.chain().getHeight(head.get()).orElse(-1L);
                    headHex = toHex(head.get());
                }
                respondJson(ex, 200, "{ \"height\": " + height + ", \"head\": \"" + headHex + "\", \"mempool\": " + mempoolSize + " }");
            } catch (Exception e) {
                respondJson(ex, 500, "{ \"error\": \"" + escape(e.getMessage()) + "\" }");
            }
        }
    }

    static final class BalanceHandler implements HttpHandler {
        private final Node node;
        BalanceHandler(Node node){ this.node = node; }

        public void handle(HttpExchange ex) throws IOException {
            Map<String,String> q = queryParams(ex.getRequestURI());
            String addr = q.get("addr");
            if (addr == null || addr.isEmpty()) {
                respondJson(ex, 400, "{ \"error\": \"missing addr\" }");
                return;
            }
            long bal = node.state().getBalance(addr);
            long nonce = node.state().getNonce(addr);
            respondJson(ex, 200, "{ \"address\":\""+escape(addr)+"\", \"balance\":"+bal+", \"nonce\":"+nonce+" }");
        }
    }

    static final class TxHandler implements HttpHandler {
        private final Node node;
        TxHandler(Node node){ this.node = node; }

        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respondJson(ex, 405, "{ \"error\":\"use POST\" }");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            try {
                Map<String,String> m = parseSimpleJson(body);
                String from = require(m, "from");
                String to = require(m, "to");
                long amount = Long.parseLong(require(m, "amountMinor"));
                long fee = Long.parseLong(require(m, "feeMinor"));
                long nonce = Long.parseLong(require(m, "nonce"));

                Transaction tx = Transaction.builder()
                        .from(from).to(to)
                        .amountMinor(amount).feeMinor(fee).nonce(nonce)
                        .build();

                node.mempool().add(tx);
                respondJson(ex, 200, "{ \"accepted\": true, \"id\": \""+ toHex(tx.id()) +"\" }");
            } catch (Exception e) {
                respondJson(ex, 400, "{ \"accepted\": false, \"error\": \"" + escape(e.getMessage()) + "\" }");
            }
        }
    }

    // ---------------- utils ----------------

    private static void respondJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static Map<String,String> queryParams(URI uri) {
        Map<String,String> out = new HashMap<String,String>();
        String q = uri.getQuery();
        if (q == null || q.isEmpty()) return out;
        String[] parts = q.split("&");
        for (int i=0;i<parts.length;i++){
            String[] kv = parts[i].split("=", 2);
            if (kv.length == 2) out.put(urlDecode(kv[0]), urlDecode(kv[1]));
        }
        return out;
    }

    private static String urlDecode(String s){
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e){ return s; }
    }

    /** ultra-simple JSON parser for a flat object with string/number fields */
    private static Map<String,String> parseSimpleJson(String json) {
        Map<String,String> out = new HashMap<String,String>();
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) throw new IllegalArgumentException("bad json");
        s = s.substring(1, s.length()-1).trim();
        if (s.isEmpty()) return out;
        // split on commas that separate pairs (no nested objects expected)
        String[] pairs = s.split("\\s*,\\s*");
        for (int i=0;i<pairs.length;i++){
            String[] kv = pairs[i].split("\\s*:\\s*", 2);
            if (kv.length != 2) continue;
            String k = stripQuotes(kv[0].trim());
            String v = kv[1].trim();
            if (v.startsWith("\"") && v.endsWith("\"")) v = stripQuotes(v);
            out.put(k, v);
        }
        return out;
    }

    private static String stripQuotes(String s){
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length()-1);
        }
        return s;
    }

    private static String require(Map<String,String> m, String key){
        String v = m.get(key);
        if (v == null) throw new IllegalArgumentException("missing field: " + key);
        return v;
    }

    private static String toHex(byte[] bytes) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j=0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    private static String escape(String s){
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
