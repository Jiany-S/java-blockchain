package io.blockchain.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainCliOptionsTest {

    @Test
    void parsesDefaults() {
        Main.CliOptions options = Main.CliOptions.parse(new String[] {});
        assertFalse(options.showHelp());
        assertNull(options.errorMessage());
        assertFalse(options.enableApi());
        assertFalse(options.enableRpc());
        assertTrue(options.enableP2p());
        assertTrue(options.demo());
        assertEquals(Path.of("./data/chain").normalize(), options.dataDir().normalize());
        assertTrue(options.p2pPeers().isEmpty());
        assertNull(options.nodeId());
    }

    @Test
    void enablesApiAndRpcWithTokens() {
        Main.CliOptions options = Main.CliOptions.parse(new String[] {
                "--enable-api",
                "--api-bind=0.0.0.0",
                "--api-port=8181",
                "--api-token=test-api",
                "--enable-rpc",
                "--rpc-bind=127.0.0.1",
                "--rpc-port=9191",
                "--rpc-token=test-rpc",
                "--no-demo",
                "--keep-alive",
                "--p2p-peer=peer1:9000",
                "--p2p-peer=peer2:9001",
                "--node-id=my-node"
        });
        assertFalse(options.showHelp());
        assertTrue(options.enableApi());
        assertEquals("0.0.0.0", options.apiBind());
        assertEquals(8181, options.apiPort());
        assertEquals("test-api", options.apiToken());
        assertTrue(options.enableRpc());
        assertEquals("127.0.0.1", options.rpcBind());
        assertEquals(9191, options.rpcPort());
        assertEquals("test-rpc", options.rpcToken());
        assertFalse(options.demo());
        assertTrue(options.keepAlive());
        assertEquals("my-node", options.nodeId());
        assertEquals(2, options.p2pPeers().size());
        assertTrue(options.p2pPeers().contains("peer1:9000"));
        assertTrue(options.p2pPeers().contains("peer2:9001"));
    }

    @Test
    void invalidDemoDurationSetsError() {
        Main.CliOptions options = Main.CliOptions.parse(new String[] {"--demo-duration-ms=-1"});
        assertTrue(options.showHelp());
        assertNotNull(options.errorMessage());
        assertTrue(options.errorMessage().contains("--demo-duration-ms"));
    }

    @Test
    void unknownFlagTriggersHelp() {
        Main.CliOptions options = Main.CliOptions.parse(new String[] {"--unknown-flag"});
        assertTrue(options.showHelp());
        assertEquals("Unknown option: --unknown-flag", options.errorMessage());
    }
}
