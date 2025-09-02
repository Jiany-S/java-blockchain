package main.java.io.blockchain.core.mempool;

import io.blockchain.core.protocol.Transaction;

import java.util.*;

/**
 * Minimal mempool:
 * - holds transactions keyed by (from, nonce) to enforce ordering
 * - iteration returns txs in FIFO by insertion (you can switch to fee-priority later)
 */
public final class Mempool {

    private final Map<String, NavigableMap<Long, Transaction>> bySender = new HashMap<String, NavigableMap<Long, Transaction>>();
    private final Deque<Transaction> fifo = new ArrayDeque<Transaction>();
    private final TxValidator validator;

    public Mempool(TxValidator validator) {
        this.validator = validator;
    }

    /** Validate and add a tx. Duplicate (same from+nonce) replaces previous. */
    public synchronized boolean add(Transaction tx) {
        validator.validate(tx);

        NavigableMap<Long, Transaction> seq = bySender.get(tx.from());
        if (seq == null) {
            seq = new TreeMap<Long, Transaction>();
            bySender.put(tx.from(), seq);
        }

        Transaction prev = seq.put(tx.nonce(), tx);
        if (prev != null) {
            // remove previous from FIFO if present
            fifo.remove(prev);
        }
        fifo.addLast(tx);
        return true;
    }

    /** Pull up to max transactions (simple FIFO for now). */
    public synchronized java.util.List<Transaction> getBatch(int max) {
        java.util.List<Transaction> out = new java.util.ArrayList<Transaction>(Math.min(max, fifo.size()));
        for (int i = 0; i < max && !fifo.isEmpty(); i++) {
            out.add(fifo.removeFirst());
        }
        // also drop from per-sender index
        for (int i = 0; i < out.size(); i++) {
            Transaction tx = out.get(i);
            NavigableMap<Long, Transaction> seq = bySender.get(tx.from());
            if (seq != null) {
                seq.remove(tx.nonce());
                if (seq.isEmpty()) bySender.remove(tx.from());
            }
        }
        return out;
    }

    /** Remove included txs by (from, nonce). */
    public synchronized void removeAll(java.util.Collection<Transaction> included) {
        for (Transaction tx : included) {
            fifo.remove(tx);
            NavigableMap<Long, Transaction> seq = bySender.get(tx.from());
            if (seq != null) {
                seq.remove(tx.nonce());
                if (seq.isEmpty()) bySender.remove(tx.from());
            }
        }
    }

    public synchronized int size() { return fifo.size(); }
}
