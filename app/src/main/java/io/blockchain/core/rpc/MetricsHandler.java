package io.blockchain.core.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.blockchain.core.metrics.BlockMetrics;
import io.blockchain.core.metrics.HttpMetrics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MetricsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getHttpContext().getPath();
        var sample = HttpMetrics.start(method, path);
        int status = 500;
        try {
            if (!"GET".equalsIgnoreCase(method)) {
                status = sendPlain(exchange, 405, "Method Not Allowed");
                return;
            }
            String metrics = BlockMetrics.scrapeMetrics();
            byte[] out = metrics.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            status = 200;
        } finally {
            HttpMetrics.stop(sample, method, path, status);
            exchange.close();
        }
    }

    private int sendPlain(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        return status;
    }
}
