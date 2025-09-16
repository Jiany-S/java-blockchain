package io.blockchain.core.state;

import io.blockchain.core.metrics.ReplayMetrics;
import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.storage.ChainStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class StateReplayer {
    private static final Logger LOG = Logger.getLogger(StateReplayer.class.getName());

    private StateReplayer(){}

    public static void replay(ChainStore chain, StateStore state, Map<String, Long> allocations) {
        if (allocations != null) {
            for (Map.Entry<String, Long> entry : allocations.entrySet()) {
                String address = entry.getKey();
                if (address == null || address.isBlank()) {
                    continue;
                }
                long amount = entry.getValue() == null ? 0L : entry.getValue();
                state.setBalance(address, amount);
                state.setNonce(address, 0L);
            }
        }

        Map<String, Long> topUps = new LinkedHashMap<>();

        for (Block block : chain.getBlocksInOrder()) {
            for (Transaction tx : block.transactions()) {
                long required = tx.amountMinor() + tx.feeMinor();
                long current = state.getBalance(tx.from());
                if (current < required) {
                    long deficit = required - current;
                    state.credit(tx.from(), deficit);
                    topUps.merge(tx.from(), deficit, Long::sum);
                    ReplayMetrics.recordTopUp(tx.from(), deficit);
                }
                state.applyTransaction(tx);
            }
        }

        if (!topUps.isEmpty()) {
            StringBuilder sb = new StringBuilder("State replay credited missing funds for ");
            boolean first = true;
            for (Map.Entry<String, Long> entry : topUps.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(entry.getKey()).append(':').append(entry.getValue());
            }
            sb.append(" (minor units)");
            LOG.warning(sb.toString());
        }
    }
}
