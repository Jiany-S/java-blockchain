package io.blockchain.core.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TransactionRoundTripTest {

    @Test
    void roundTrip() {
        Transaction tx = Transaction.builder()
                .from("alice123456").to("bob654321")
                .amountMinor(123).feeMinor(1).nonce(0).timestamp(System.currentTimeMillis())
                .build();

        byte[] bytes = tx.serialize();
        Transaction tx2 = TransactionCodec.fromBytes(bytes);

        assertEquals(tx.amountMinor(), tx2.amountMinor());
        assertEquals(tx.feeMinor(), tx2.feeMinor());
        assertEquals(tx.nonce(), tx2.nonce());
        assertEquals(tx.from(), tx2.from());
        assertEquals(tx.to(), tx2.to());
        // id should be stable (unsigned bytes hash)
        assertArrayEquals(tx.id(), tx2.id());
    }
}
