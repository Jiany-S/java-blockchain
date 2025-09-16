package io.blockchain.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

public final class ReplayMetrics {
    private static final Counter topUpEvents;
    private static final DistributionSummary topUpAmount;

    static {
        MeterRegistry registry = BlockMetrics.registry();
        topUpEvents = Counter.builder("replay.topup.events")
                .description("Number of replay balance top-up events")
                .register(registry);
        topUpAmount = DistributionSummary.builder("replay.topup.amount")
                .baseUnit("minor")
                .description("Total amount credited during state replay")
                .register(registry);
    }

    private ReplayMetrics() {}

    public static void recordTopUp(String address, long amountMinor) {
        topUpEvents.increment();
        topUpAmount.record(amountMinor);
    }
}
