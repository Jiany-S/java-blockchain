package io.blockchain.core.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Block = header + list of transactions.
 * We keep body encoding simple: header bytes first, then N, then each tx as its signed bytes.
 */
public final class Block {
    private final BlockHeader header;
    private final List<Transaction> transactions;

    public Block(BlockHeader header, List<Transaction> txs) {
        this.header = header;
        this.transactions = txs != null ? List.copyOf(txs) : List.of();
        basicValidate();
    }

    public BlockHeader header() { return header; }
    public List<Transaction> transactions() { return transactions; }

    /** Deterministic signed encoding: header || count || tx[i].serialize() */
    public byte[] serialize() {
        int size = header.serialize().length + 4;
        for (Transaction tx : transactions) size += 4 + tx.serialize().length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(header.serialize());
        buf.putInt(transactions.size());
        for (Transaction tx : transactions) {
            byte[] b = tx.serialize();
            buf.putInt(b.length);
            buf.put(b);
        }
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    /**
     * Compute Merkle root over TX IDs (unsigned-hash form),
     * which matches our Transaction.id policy.
     */
    public byte[] computeMerkleRoot() {
        List<byte[]> leaves = new ArrayList<>(transactions.size());
        for (Transaction tx : transactions) leaves.add(tx.id());
        return Merkle.rootOf(leaves);
    }

    public void basicValidate() {
        if (header == null) throw new IllegalArgumentException("missing header");
        if (transactions.size() > 1_000_000) throw new IllegalArgumentException("too many txs"); // sanity cap
        // Optional: enforce header.merkleRoot matches computed root here (or later in consensus)
    }

    @Override public String toString() {
        return "Block{height=" + header.height() + ", txs=" + transactions.size() + "}";
    }
}
