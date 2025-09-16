package io.blockchain.core.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.function.Supplier;

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
        StringBuilder sb = new StringBuilder();
        for (Meter m : registry.getMeters()) {
            for (Measurement meas : m.measure()) {
                sb.append(m.getId().getName())
                  .append("{stat=")
                  .append(meas.getStatistic())
                  .append("} ")
                  .append(meas.getValue())
                  .append("\n");
            }
        }
        return sb.toString();
    }

    public static MeterRegistry registry() {
        return registry;
    }
}
