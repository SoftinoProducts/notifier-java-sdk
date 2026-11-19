package com.softino.notifier;

import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Backward-compatibility guards for the 1.1.x constructors and defaults, plus validation of the
 * new async options. No network calls.
 */
public class CompatibilityTest {

    @Test
    public void legacyConstructorsKeepTheirBlockingTimeouts() {
        NotifierApi defaults = new NotifierApi("key");
        try {
            assertEquals(10_000, defaults.getConfig().getBlockingConnectTimeoutMs());
            assertEquals(30_000, defaults.getConfig().getBlockingReadTimeoutMs());
            assertEquals(20, defaults.getConfig().getMaxConnections());
            // Async defaults are independent and are the user-facing ones.
            assertEquals(2_000, defaults.getConfig().getConnectTimeoutMs());
            assertEquals(5_000, defaults.getConfig().getReadTimeoutMs());
            assertEquals(10_000, defaults.getConfig().getDefaultDeadlineMs());
        } finally {
            defaults.close();
        }

        NotifierApi custom = new NotifierApi("key", "https://api.example.com", 5_000, 15_000, 50);
        try {
            assertEquals(5_000, custom.getConfig().getBlockingConnectTimeoutMs());
            assertEquals(15_000, custom.getConfig().getBlockingReadTimeoutMs());
            assertEquals(50, custom.getConfig().getMaxConnections());
        } finally {
            custom.close();
        }

        // Apache's blocking transport treats 0 as infinite/unlimited; the old constructors
        // accepted it and must keep doing so.
        NotifierApi zero = new NotifierApi("key", "https://api.example.com", 0, 0, 20);
        try {
            assertEquals(0, zero.getConfig().getBlockingConnectTimeoutMs());
            assertEquals(0, zero.getConfig().getBlockingReadTimeoutMs());
            assertEquals(20, zero.getConfig().getMaxConnections());
        } finally {
            zero.close();
        }
    }

    @Test
    public void configDefaultsPreserveBlockingBehaviourAndExposeAsyncDefaults() {
        NotifierApi api = new NotifierApi(NotifierConfig.builder("key").build());
        try {
            assertEquals(10_000, api.getConfig().getBlockingConnectTimeoutMs());
            assertEquals(30_000, api.getConfig().getBlockingReadTimeoutMs());
            assertEquals(2_000, api.getConfig().getConnectTimeoutMs());
        } finally {
            api.close();
        }
    }

    @Test
    public void shutdownHookIsOptionalAndCloseIsIdempotent() {
        NotifierApi api = new NotifierApi(NotifierConfig.builder("key")
                .registerShutdownHook(true)
                .build());
        api.close();
        api.close(); // idempotent; the hook was removed by the first close
    }

    @Test
    public void invalidSendOptionsFailFast() {
        try {
            SendOptions.builder().deadline(Duration.ofMillis(-1)).build();
            fail("expected a negative deadline to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            SendOptions.builder().maxRetries(-1).build();
            fail("expected a negative retry cap to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
