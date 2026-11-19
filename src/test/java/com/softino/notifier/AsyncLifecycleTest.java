package com.softino.notifier;

import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.exception.NotifierQueueFullException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Lifecycle tests: close/drain, no leaked threads, bounded queues and fixed thread counts under
 * a 10k-send burst.
 */
public class AsyncLifecycleTest {

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
                .closeDrainMillis(300)
                .retryBaseDelayMillis(10)
                .retryMaxDelayMillis(30);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Thread> sdkThreads() {
        List<Thread> out = new ArrayList<Thread>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("notifier-")) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    public void closeResolvesEveryAcceptedFutureAndLeavesNoThreads() throws Exception {
        hub.handler(exchange -> {
            sleep(400);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                .bulkQueueCapacity(1_000)
                .bulkPrioritySenderThreads(2)
                .bulkMaxInFlight(8)
                .closeDrainMillis(150)
                .build());

        List<CompletableFuture<SendResult>> futures = new ArrayList<CompletableFuture<SendResult>>();
        for (int i = 0; i < 300; i++) {
            futures.add(api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456"));
        }

        api.close();

        int completed = 0;
        int failed = 0;
        for (CompletableFuture<SendResult> future : futures) {
            assertTrue("every accepted future must be settled by close()", future.isDone());
            try {
                future.get(1, TimeUnit.SECONDS);
                completed++;
            } catch (ExecutionException expected) {
                assertTrue("close() failures must be typed, got " + expected.getCause(),
                        expected.getCause() instanceof NotifierClosedException);
                failed++;
            }
            assertTrue("a second get must not hang", future.isDone());
        }

        NotifierStats stats = api.stats();
        assertEquals(300, stats.getAccepted());
        assertEquals("every accepted send must be accounted for",
                stats.getAccepted(), stats.getCompleted() + stats.getFailed());
        assertTrue("the test should have observed both outcomes or a clean drain",
                completed + failed == 300);

        // No SDK thread or connection may survive close().
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sdkThreads().isEmpty() && System.nanoTime() < deadline) {
            sleep(20);
        }
        assertTrue("SDK threads survived close(): " + sdkThreads(), sdkThreads().isEmpty());

        // New sends after close fail locally, with typed exceptions.
        try {
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");
            fail("expected NotifierClosedException");
        } catch (NotifierClosedException expected) {
            // expected
        }
        CompletableFuture<SendResult> afterClose =
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456");
        assertTrue(afterClose.isDone());
        try {
            afterClose.get();
            fail("expected a failed future");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof NotifierClosedException);
        }
    }

    @Test
    public void closeDrainsWhenTheHubIsHealthy() throws Exception {
        NotifierApi api = new NotifierApi(config().closeDrainMillis(5_000).build());
        List<CompletableFuture<SendResult>> futures = new ArrayList<CompletableFuture<SendResult>>();
        for (int i = 0; i < 100; i++) {
            futures.add(api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456"));
        }
        api.close();

        int success = 0;
        for (CompletableFuture<SendResult> future : futures) {
            try {
                assertNotNull(future.get(1, TimeUnit.SECONDS));
                success++;
            } catch (ExecutionException ignored) {
                // A shutdown at the very end may refuse one; the counters still must agree.
            }
        }
        NotifierStats stats = api.stats();
        assertEquals(100, stats.getAccepted());
        assertEquals(100, stats.getCompleted() + stats.getFailed());
        assertEquals("a healthy close must drain accepted work", 100, success);
    }

    @Test
    public void tenThousandConcurrentSendsStayBounded() throws Exception {
        hub.handler(exchange -> {
            sleep(2);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        final NotifierApi api = new NotifierApi(config()
                .bulkQueueCapacity(20_000)
                .highPriorityQueueCapacity(1_000)
                .bulkPrioritySenderThreads(2)
                .highPrioritySenderThreads(1)
                .bulkMaxInFlight(16)
                .highPriorityMaxInFlight(4)
                .maxRetries(0)
                .build());

        final AtomicInteger maxSdkThreads = new AtomicInteger();
        final AtomicInteger maxQueueDepth = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();

        Thread monitor = new Thread(() -> {
            while (!api.isClosed()) {
                maxSdkThreads.accumulateAndGet(sdkThreads().size(), Math::max);
                maxQueueDepth.accumulateAndGet(
                        api.stats().getBulkQueueDepth() + api.stats().getHighQueueDepth(), Math::max);
                sleep(1);
            }
        }, "test-monitor");
        monitor.setDaemon(true);
        monitor.start();

        List<CompletableFuture<SendResult>> futures = new ArrayList<CompletableFuture<SendResult>>();
        try {
            for (int i = 0; i < 10_000; i++) {
                CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(
                        ChannelType.SMS, "+989120000000", "otp", "123456");
                futures.add(future);
                if (future.isCompletedExceptionally()) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof NotifierQueueFullException) {
                            rejected.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                } else {
                    accepted.incrementAndGet();
                }
            }

            assertEquals("the 20k queue must accept all 10k sends", 0, rejected.get());
            assertEquals("all 10k sends must be accepted", 10_000, accepted.get());
            assertTrue("queue exceeded its configured capacity: " + maxQueueDepth.get(),
                    maxQueueDepth.get() <= 21_000);
            assertTrue("SDK thread count grew with send rate: " + maxSdkThreads.get(),
                    maxSdkThreads.get() <= 10);

            assertTrue("accepted work did not drain", api.awaitIdle(Duration.ofSeconds(20)));
            for (CompletableFuture<SendResult> future : futures) {
                assertTrue("all futures must settle", future.isDone());
            }
        } finally {
            api.close();
            monitor.join(2_000);
        }

        assertEquals(accepted.get(), api.stats().getAccepted());
        assertEquals(rejected.get(), api.stats().getRejected());
    }

    @Test
    public void closeSettlesAQueuedRetry() throws Exception {
        hub.handler(exchange -> TestServer.respondJson(exchange, 503, "{\"error\":\"down\"}"));

        NotifierApi api = new NotifierApi(config()
                .maxRetries(1)
                .retryBaseDelayMillis(5_000)
                .retryMaxDelayMillis(5_000)
                .hubSupportsIdempotencyKey(true)
                .closeDrainMillis(50)
                .build());
        try {
            CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(
                    ChannelType.SMS, "+989120000000", "otp", "123456");

            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (api.stats().getRetries() == 0 && System.nanoTime() < waitUntil) {
                sleep(10);
            }
            assertEquals("the retry must be waiting on the scheduler", 1, api.stats().getRetries());

            api.close();
            assertTrue("close() must settle a queued retry", future.isDone());
            try {
                future.get(1, TimeUnit.SECONDS);
                fail("expected NotifierClosedException");
            } catch (ExecutionException expected) {
                assertTrue("got " + expected.getCause(),
                        expected.getCause() instanceof NotifierClosedException);
            }
            assertEquals(api.stats().getAccepted(),
                    api.stats().getCompleted() + api.stats().getFailed());
        } finally {
            api.close();
        }
    }

    @Test
    public void sdkThreadsAreDaemonAndNamed() throws Exception {
        NotifierApi api = new NotifierApi(config().build());
        try {
            api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                    .get(3, TimeUnit.SECONDS);
            List<Thread> threads = sdkThreads();
            assertTrue("expected SDK threads, got " + threads, !threads.isEmpty());
            for (Thread thread : threads) {
                assertTrue(thread.getName(), thread.isDaemon());
                assertTrue("unexpected thread name " + thread.getName(),
                        thread.getName().matches("notifier-(sender-(high|bulk)|reactor|retry|deadline)-\\d+"));
            }
        } finally {
            api.close();
        }
    }
}
