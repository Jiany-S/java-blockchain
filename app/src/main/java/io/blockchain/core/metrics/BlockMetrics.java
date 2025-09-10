package io.blockchain.core.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class BlockMetrics {
    private static final MeterRegistry registry = new SimpleMeterRegistry();
    private static final Counter blocksMined = registry.counter("blocks.mined");
    private static final Timer miningTime = registry.timer("block.mining.time");

    public static <T> T recordMining(Supplier<T> blockProductionLogic) {
        return miningTime.record(blockProductionLogic);
    }

    public static void incrementBlocks() {
        blocksMined.increment();
    }

    public static String scrapeMetrics() {
        return registry.getMeters().stream()
                .flatMap(m -> m.measure().stream())
                .map(m -> m.getStatistic() + ": " + m.getValue())
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
