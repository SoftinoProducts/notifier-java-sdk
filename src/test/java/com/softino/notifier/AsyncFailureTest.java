package com.softino.notifier;

import com.softino.notifier.exception.NotifierInvalidRequestException;
import com.softino.notifier.exception.NotifierTimeoutException;
import com.softino.notifier.exception.NotifierUnavailableException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Retry-safety and failure-mapping tests: a duplicated SMS is worse than a missing one, so the
 * SDK must only retry failures that cannot have produced a delivery.
 */
public class AsyncFailureTest {

    private TestServer hub;

    @Before
    public void start() throws Exception {
        hub = new TestServer(exchange -> TestServer.respondJson(exchange, 202, sendJson()));
    }

    @After
    public void stop() {
        if (hub != null) {
            hub.close();
        }
    }

    private static String sendJson() {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\","
                + "\"channel_type\":\"sms\"}";
    }

    private NotifierConfig.Builder config() {
        return NotifierConfig.builder("test-key")
                .baseUrl(hub.baseUrl())
                .closeDrainMillis(100)
                .retryBaseDelayMillis(15)
                .retryMaxDelayMillis(60);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void serverErrorIsNotRetriedByDefault() throws Exception {
        hub.handler(exchange -> TestServer.respondJson(exchange, 503, "{\"error\":\"unavailable\"}"));

        NotifierApi api = new NotifierApi(config().maxRetries(2).hubSupportsIdempotencyKey(false).build());
        try {
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof NotifierUnavailableException);
                assertTrue(((NotifierUnavailableException) expected.getCause()).isRetryable());
            }
            assertEquals("without idempotency support, a 5xx must not be retried",
                    1, hub.requestCount());
            assertEquals(0, api.stats().getRetries());
        } finally {
            api.close();
        }
    }

    @Test
    public void serverErrorIsRetriedWithSameIdempotencyKeyWhenSupported() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        hub.handler(exchange -> {
            if (calls.incrementAndGet() == 1) {
                TestServer.respondJson(exchange, 503, "{\"error\":\"unavailable\"}");
            } else {
                TestServer.respondJson(exchange, 202, sendJson());
            }
        });

        NotifierApi api = new NotifierApi(config().maxRetries(2).hubSupportsIdempotencyKey(true).build());
        try {
            SendResult result = api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, hub.requestCount());
            assertEquals(1, api.stats().getRetries());
            assertNotNull(result.getIdempotencyKey());

            List<TestServer.Recorded> requests = hub.requests();
            String firstKey = requests.get(0).header("Idempotency-Key");
            String secondKey = requests.get(1).header("Idempotency-Key");
            assertNotNull(firstKey);
            assertEquals("every retry must carry the same key", firstKey, secondKey);
            assertEquals(result.getIdempotencyKey(), firstKey);
            assertTrue(requests.get(1).body.contains(firstKey));
        } finally {
            api.close();
        }
    }

    @Test
    public void readTimeoutIsNotRetriedWithoutIdempotencySupport() throws Exception {
        hub.handler(exchange -> {
            sleep(600);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                .asyncReadTimeoutMillis(120)
                .maxRetries(2)
                .hubSupportsIdempotencyKey(false)
                .build());
        try {
            long start = System.nanoTime();
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a timeout");
            } catch (ExecutionException expected) {
                assertTrue("got " + expected.getCause(),
                        expected.getCause() instanceof NotifierTimeoutException);
                assertTrue(((NotifierTimeoutException) expected.getCause()).isRetryable());
            }
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(900));
            assertEquals("an ambiguous read timeout must not be retried without idempotency",
                    1, hub.requestCount());
        } finally {
            api.close();
        }
    }

    @Test
    public void readTimeoutIsRetriedWithSameKeyWhenIdempotencyIsSupported() throws Exception {
        hub.handler(exchange -> {
            sleep(400);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                .asyncReadTimeoutMillis(100)
                .maxRetries(1)
                .hubSupportsIdempotencyKey(true)
                .build());
        try {
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a timeout");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof NotifierTimeoutException);
            }
            assertEquals(2, hub.requestCount());
            List<TestServer.Recorded> requests = hub.requests();
            assertEquals(requests.get(0).header("Idempotency-Key"),
                    requests.get(1).header("Idempotency-Key"));
        } finally {
            api.close();
        }
    }

    @Test
    public void deadlineIsEnforcedWhileResponseBodyDribbles() throws Exception {
        hub.handler(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, 0); // chunked: headers arrive, body dribbles
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    os.write(' ');
                    os.flush();
                    sleep(100);
                }
            } catch (RuntimeException ignored) {
                // client cancelled at the deadline
            }
        });

        NotifierApi api = new NotifierApi(config()
                // A socket timeout alone cannot bound this: every byte resets it.
                .asyncReadTimeoutMillis(5_000)
                .maxRetries(0)
                .build());
        try {
            long start = System.nanoTime();
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp",
                        SendOptions.builder().deadline(Duration.ofMillis(400)).build(), "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected the total deadline to fire while the body was still arriving");
            } catch (ExecutionException expected) {
                assertTrue("expected NotifierTimeoutException, got " + expected.getCause(),
                        expected.getCause() instanceof NotifierTimeoutException);
                assertTrue(((NotifierTimeoutException) expected.getCause()).isRetryable());
            }
            long elapsed = System.nanoTime() - start;
            assertTrue("deadline must fire near 400ms, took " + elapsed / 1_000_000 + "ms",
                    elapsed < TimeUnit.MILLISECONDS.toNanos(1_500));
            assertEquals(1, api.stats().getFailed());
        } finally {
            api.close();
        }
    }

    @Test
    public void invalidRequestIsNeverRetried() throws Exception {
        hub.handler(exchange -> TestServer.respondJson(exchange, 400, "{\"error\":\"bad template\"}"));

        NotifierApi api = new NotifierApi(config().maxRetries(3).hubSupportsIdempotencyKey(true).build());
        try {
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof NotifierInvalidRequestException);
                assertFalse(((NotifierInvalidRequestException) expected.getCause()).isRetryable());
            }
            assertEquals(1, hub.requestCount());
        } finally {
            api.close();
        }
    }

    @Test
    public void deadlineStopsRetriesEvenWhenIdempotencyIsSupported() throws Exception {
        hub.handler(exchange -> {
            sleep(800);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                // Longer than the deadline: the first attempt can only end at the deadline, so
                // no retry may be scheduled after it.
                .asyncReadTimeoutMillis(5_000)
                .maxRetries(5)
                .hubSupportsIdempotencyKey(true)
                .build());
        try {
            long start = System.nanoTime();
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp",
                        SendOptions.builder().deadline(Duration.ofMillis(250)).build(), "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a timeout");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof NotifierTimeoutException);
            }
            assertTrue("must stop at the deadline, took " + (System.nanoTime() - start) / 1_000_000 + "ms",
                    System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1_200));
            assertEquals(1, hub.requestCount());
        } finally {
            api.close();
        }
    }

    @Test
    public void connectionRefusedIsRetriedEvenWithoutIdempotencySupport() throws Exception {
        String deadBaseUrl = hub.baseUrl();
        hub.close();
        hub = null;

        NotifierApi api = new NotifierApi(NotifierConfig.builder("test-key")
                .baseUrl(deadBaseUrl)
                .closeDrainMillis(50)
                .maxRetries(2)
                .retryBaseDelayMillis(10)
                .retryMaxDelayMillis(20)
                .defaultDeadlineMillis(1_500)
                .build());
        try {
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof NotifierUnavailableException);
            }
            assertEquals("a refused connection is safe to retry", 2, api.stats().getRetries());
        } finally {
            api.close();
        }
    }

    @Test
    public void fireAndForgetNeverThrowsNetworkFailures() throws Exception {
        hub.handler(exchange -> TestServer.respondJson(exchange, 500, "{\"error\":\"boom\"}"));

        NotifierApi api = new NotifierApi(config().maxRetries(0).build());
        try {
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");
            assertTrue(api.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(1, api.stats().getDropped());
        } finally {
            api.close();
        }
    }
}
