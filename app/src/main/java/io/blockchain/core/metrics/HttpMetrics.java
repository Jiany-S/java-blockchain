package io.blockchain.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class HttpMetrics {
    private static final MeterRegistry REGISTRY = BlockMetrics.registry();

    private HttpMetrics() {}

    public static Timer.Sample start(String method, String path) {
        return Timer.start(REGISTRY);
    }

    public static void stop(Timer.Sample sample, String method, String path, int status) {
        Timer timer = Timer
                .builder("http.server.requests")
                .description("HTTP server request duration")
                .tag("method", method)
                .tag("path", path)
                .tag("status", Integer.toString(status))
                .register(REGISTRY);
        sample.stop(timer);
    }
}
