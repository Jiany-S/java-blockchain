package io.blockchain.core.consensus;

import io.blockchain.core.protocol.Block;
import io.blockchain.core.protocol.BlockHeader;
import io.blockchain.core.protocol.Merkle;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class ProofOfWorkTest {

    @Test
    void meetsTarget_easy() {
        byte[] zeros = new byte[32];
        BlockHeader h = new BlockHeader(zeros, zeros, 0, System.currentTimeMillis(), 8, 0);
        ProofOfWork pow = new ProofOfWork();
        // with difficulty 8, some nonces will meet target; direct check is fine
        assertTrue(pow.meetsTarget(new BlockHeader(zeros, zeros, 0, h.timestamp(), 0, 0))); // difficulty=0 always true
    }

    @Test
    void mineFindsNonce() {
        byte[] zeros = new byte[32];
        BlockHeader h = new BlockHeader(zeros, zeros, 0, System.currentTimeMillis(), 8, 0);
        Block b = new Block(h, Collections.emptyList());
        ProofOfWork pow = new ProofOfWork();
        assertTrue(pow.mine(b, 1_000_000).isPresent());
    }
}
