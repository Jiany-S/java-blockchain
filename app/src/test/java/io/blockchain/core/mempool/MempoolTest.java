package io.blockchain.core.mempool;

import io.blockchain.core.protocol.Transaction;
import io.blockchain.core.state.InMemoryStateStore;
import io.blockchain.core.state.StateStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MempoolTest {

    @Test
    void addRejectsWhenBalanceInsufficient() {
        StateStore state = new InMemoryStateStore();
        TxValidator validator = new TxValidator(state, 1L);
        Mempool mempool = new Mempool(validator);

        Transaction tx = baseTxBuilder()
                .amountMinor(10)
                .feeMinor(1)
                .build();

        assertThrows(IllegalArgumentException.class, () -> mempool.add(tx));
        assertEquals(0, mempool.size());
    }

    @Test
    void addRejectsWhenFeeBelowMinimum() {
        StateStore state = new InMemoryStateStore();
        state.setBalance("alice", 20);
        TxValidator validator = new TxValidator(state, 1L);
        Mempool mempool = new Mempool(validator);

        Transaction tx = baseTxBuilder()
                .amountMinor(10)
                .feeMinor(0)
                .build();

        assertThrows(IllegalArgumentException.class, () -> mempool.add(tx));
        assertEquals(0, mempool.size());
    }

    @Test
    void addAcceptsValidTransaction() {
        StateStore state = new InMemoryStateStore();
        state.setBalance("alice", 50);
        TxValidator validator = new TxValidator(state, 1L);
        Mempool mempool = new Mempool(validator);

        Transaction tx = baseTxBuilder()
                .amountMinor(10)
                .feeMinor(1)
                .build();

        assertTrue(mempool.add(tx));
        assertEquals(1, mempool.size());
    }

    private static Transaction.Builder baseTxBuilder() {
        return Transaction.builder()
                .from("alice")
                .to("bob")
                .amountMinor(1)
                .feeMinor(1)
                .nonce(0);
    }
}
