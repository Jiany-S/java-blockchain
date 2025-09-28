package io.blockchain.core.consensus;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Hashes;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Minimal Proof-of-Work:
 * - Interprets header.difficultyOrSlot as "required leading zero BITS" in the header hash.
 * - Hash = SHA-256(header.serialize()).
 *
 * Example:
 *   difficultyOrSlot = 16  -> hash must start with at least 16 zero bits (two 0x00 bytes).
 *
 * Notes:
 * - BlockHeader in your protocol is immutable, so mining creates a NEW header each try.
 * - We keep timestamp constant by default; you can enable occasional updates if you want.
 */
public final class ProofOfWork {

    /** Quick check: does this header meet its difficulty requirement? */
    public boolean meetsTarget(BlockHeader header) {
        int requiredBits = toRequiredBits(header.difficultyOrSlot());
        byte[] hash = Hashes.sha256(header.serialize());
        return hasLeadingZeroBits(hash, requiredBits);
    }

    /**
     * Try to mine a block by incrementing the nonce up to maxTries.
     * Returns Optional.of(minedBlock) if found; Optional.empty() otherwise.
     *
     * The returned Block is a NEW instance with a header having the winning nonce.
     */
    public Optional<Block> mine(Block template, long maxTries) {
        if (template == null) return Optional.empty();

        BlockHeader h = template.header();
        int requiredBits = toRequiredBits(h.difficultyOrSlot());

        long startNonce = h.nonce();
        long ts = h.timestamp(); // fixed for now; can update periodically if desired

        // Tight loop: rebuild header with incremented nonce; check hash
        long nonce = startNonce;
        for (long i = 0; i < maxTries; i++, nonce++) {
            BlockHeader candidate = new BlockHeader(
                    h.parentHash(),
                    h.merkleRoot(),
                    h.height(),
                    ts,
                    h.difficultyOrSlot(),
                    nonce
            );
            byte[] hash = Hashes.sha256(candidate.serialize());
            if (hasLeadingZeroBits(hash, requiredBits)) {
                // Found a valid nonce: return a NEW Block with the candidate header
                Block mined = new Block(candidate, template.transactions());
                return Optional.of(mined);
            }
        }
        return Optional.empty();
    }

    // ---------- helpers ----------

    /**
     * Work approximation for a block header using the configured difficulty bits.
     * Higher difficulty yields exponentially more work.
     */
    public static BigInteger calculateBlockWork(BlockHeader header) {
        if (header == null) {
            return BigInteger.ZERO;
        }
        int requiredBits = toRequiredBits(header.difficultyOrSlot());
        if (requiredBits <= 0) {
            return BigInteger.ONE;
        }
        return BigInteger.ONE.shiftLeft(requiredBits);
    }

    /** Clamp difficulty to a sane non-negative int. */
    private static int toRequiredBits(long difficultyOrSlot) {
        if (difficultyOrSlot < 0) return 0;
        if (difficultyOrSlot > 256) return 256; // SHA-256 cap
        return (int) difficultyOrSlot;
    }

    /**
     * Check for N leading zero bits in the hash.
     * Fast path: count whole zero bytes, then the first non-zero byte's leading zeros.
     */
    private static boolean hasLeadingZeroBits(byte[] hash, int requiredBits) {
        if (requiredBits <= 0) return true;
        if (requiredBits > 256) return false;

        int fullBytes = requiredBits / 8;
        int remBits = requiredBits % 8;

        // All required full bytes must be 0x00
        for (int i = 0; i < fullBytes; i++) {
            if (hash[i] != 0) return false;
        }
        if (remBits == 0) return true;

        // Check leading bits in the next byte
        int next = hash[fullBytes] & 0xff;
        int leading = leadingZeroBitsInByte(next);
        return leading >= remBits;
    }

    /** Number of leading zero bits in a single byte (0..8). */
    private static int leadingZeroBitsInByte(int b) {
        if (b == 0) return 8;
        int n = 0;
        for (int mask = 0x80; mask != 0; mask >>= 1) {
            if ((b & mask) == 0) n++; else break;
        }
        return n;
    }
}
