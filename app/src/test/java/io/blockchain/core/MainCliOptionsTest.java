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
        assertTrue(options.demo());
        assertEquals(Path.of("./data/chain").normalize(), options.dataDir().normalize());
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
                "--keep-alive"
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
