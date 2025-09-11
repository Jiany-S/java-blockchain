package io.blockchain.core.rpc;

import io.blockchain.core.metrics.BlockMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MetricsHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {
        String m = BlockMetrics.scrapeMetrics();
        byte[] out = m.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }
}
