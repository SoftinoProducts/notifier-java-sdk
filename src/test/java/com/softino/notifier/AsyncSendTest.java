package com.softino.notifier;

import com.softino.notifier.exception.NotifierQueueFullException;
import com.softino.notifier.exception.NotifierTimeoutException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavioural tests for the non-blocking send path: caller-thread latency, off-thread
 * completion, idempotency, priority lanes, queue backpressure and typed deadlines.
 */
public class AsyncSendTest {

    private TestServer hub;

    @Before
    public void start() throws Exception {
        hub = new TestServer(exchange -> {
            TestServer.respondJson(exchange, 202, sendJson());
        });
    }

    @After
    public void stop() {
        if (hub != null) hub.close();
    }

    private static String sendJson() {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\","
                + "\"channel_type\":\"sms\",\"recipient\":\"+989120000000\",\"body\":\"hi\"}";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NotifierConfig.Builder config() {
        return NotifierConfig.builder("test-key")
                .baseUrl(hub.baseUrl())
                .closeDrainMillis(200)
                .retryBaseDelayMillis(20)
                .retryMaxDelayMillis(100);
    }

    @Test
    public void sendAsyncReturnsBeforeHubAnswersAndCompletesOnSdkThread() throws Exception {
        hub.handler(exchange -> {
            sleep(600);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        final AtomicReference<String> completedOn = new AtomicReference<String>();
        final AtomicInteger completedCount = new AtomicInteger();
        NotifierConfig config = config()
                .sendListener(new SendListener() {
                    @Override
                    public void onCompleted(SendEvent event, SendOutcome outcome, long durationMillis,
                                            com.softino.notifier.exception.NotifierException error) {
                        completedOn.set(Thread.currentThread().getName());
                        completedCount.incrementAndGet();
                    }
                })
                .build();
        NotifierApi api = new NotifierApi(config);
        try {
            long start = System.nanoTime();
            CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(
                    ChannelType.SMS, "+989120000000", "otp",
                    SendOptions.builder().traceId("sale-123").deadline(Duration.ofSeconds(3)).build(),
                    "987654");
            long submitNanos = System.nanoTime() - start;

            assertTrue("submit must not wait for the hub, took " + submitNanos / 1_000_000 + "ms",
                    submitNanos < TimeUnit.MILLISECONDS.toNanos(100));

            SendResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result.getId());
            assertNotNull("async sends always return an idempotency key", result.getIdempotencyKey());
            assertTrue("listener must finish after the future settles",
                    api.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(1, completedCount.get());
            assertNotNull(completedOn.get());
            assertTrue("completion ran on " + completedOn.get(),
                    completedOn.get().startsWith("notifier-"));

            TestServer.Recorded request = hub.lastRequest();
            assertEquals("POST", request.method);
            assertEquals("/v1/notifications", request.path);
            assertEquals(result.getIdempotencyKey(), request.header("Idempotency-Key"));
            assertEquals("bulk", request.header("X-Priority"));
            assertEquals("sale-123", request.header("X-Trace-Id"));
            assertTrue(request.body.contains("\"template_name\":\"otp\""));
            assertTrue(request.body.contains(result.getIdempotencyKey()));
        } finally {
            api.close();
        }
    }

    @Test
    public void callerReturnStaysInMicrosecondsUnderBurst() throws Exception {
        hub.handler(exchange -> {
            sleep(200);
            TestServer.respondJson(exchange, 202, sendJson());
        });
        NotifierApi api = new NotifierApi(config()
                .bulkQueueCapacity(5_000)
                .closeDrainMillis(50)
                .maxRetries(0)
                .build());
        try {
            // Warm the engine (first call creates the reactor/dispatcher threads).
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");

            int calls = 1_000;
            long[] returns = new long[calls];
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                long t0 = System.nanoTime();
                api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");
                returns[i] = System.nanoTime() - t0;
            }
            long wall = System.nanoTime() - start;
            java.util.Arrays.sort(returns);
            long p50 = returns[calls / 2];
            long p99 = returns[(int) (calls * 0.99)];

            assertTrue("p50 must stay in the microsecond range, was "
                    + p50 / 1_000 + "µs", p50 < TimeUnit.MILLISECONDS.toNanos(5));
            assertTrue("p99 must stay well below a millisecond-scale budget, was "
                    + p99 / 1_000 + "µs", p99 < TimeUnit.MILLISECONDS.toNanos(25));
            assertTrue("enqueueing 1000 sends must not take a hub round trip, was "
                    + wall / 1_000_000 + "ms", wall < TimeUnit.SECONDS.toNanos(2));
            assertEquals(1_001, api.stats().getAccepted());
        } finally {
            api.close();
        }
    }

    @Test
    public void forgetReturnsImmediatelyAndCountsDroppedOnFailure() throws Exception {
        hub.handler(exchange -> TestServer.respondJson(exchange, 500,
                "{\"error\":\"boom\",\"code\":5001}"));

        NotifierApi api = new NotifierApi(config().maxRetries(0).build());
        try {
            long start = System.nanoTime();
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "987654");
            long elapsed = System.nanoTime() - start;
            assertTrue("forget must return immediately, took " + elapsed / 1_000_000 + "ms",
                    elapsed < TimeUnit.MILLISECONDS.toNanos(100));

            assertTrue(api.awaitIdle(Duration.ofSeconds(3)));
            NotifierStats stats = api.stats();
            assertEquals(1, stats.getAccepted());
            assertEquals(1, stats.getFailed());
            assertEquals(1, stats.getDropped());
        } finally {
            api.close();
        }
    }

    @Test
    public void hubDownFailsFastInsteadOfWaitingForSocketTimeout() throws Exception {
        String deadBaseUrl = hub.baseUrl();
        hub.close();
        hub = null;

        NotifierApi api = new NotifierApi(NotifierConfig.builder("test-key")
                .baseUrl(deadBaseUrl)
                .closeDrainMillis(100)
                .defaultDeadlineMillis(800)
                .maxRetries(0)
                .build());
        try {
            long start = System.nanoTime();
            CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(
                    ChannelType.SMS, "+989120000000", "otp", "123456");
            long submitNanos = System.nanoTime() - start;
            assertTrue(submitNanos < TimeUnit.MILLISECONDS.toNanos(100));

            try {
                future.get(3, TimeUnit.SECONDS);
                fail("expected the send to fail");
            } catch (ExecutionException expected) {
                Throwable cause = expected.getCause();
                assertTrue("expected a typed SDK failure, got " + cause,
                        cause instanceof com.softino.notifier.exception.NotifierException);
                assertTrue("connection failure should be retryable",
                        ((com.softino.notifier.exception.NotifierException) cause).isRetryable());
            }
            long elapsed = System.nanoTime() - start;
            assertTrue("a refused connection must not wait for a read timeout, took "
                            + elapsed / 1_000_000 + "ms",
                    elapsed < TimeUnit.MILLISECONDS.toNanos(1_000));
        } finally {
            api.close();
        }
    }

    @Test
    public void fullQueueThrowsTypedLocalExceptionAndNeverBlocks() throws Exception {
        hub.handler(exchange -> {
            sleep(2_000);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                .highPriorityQueueCapacity(1)
                .highPrioritySenderThreads(1)
                .highPriorityMaxInFlight(1)
                .highPriorityQueueCapacity(1)
                .closeDrainMillis(100)
                .build());
        SendOptions high = SendOptions.builder().priority(SendPriority.HIGH)
                .deadline(Duration.ofSeconds(2)).build();
        try {
            NotifierQueueFullException full = null;
            long start = System.nanoTime();
            for (int i = 0; i < 12 && full == null; i++) {
                try {
                    api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", high, "123456");
                } catch (NotifierQueueFullException e) {
                    full = e;
                }
            }
            long elapsed = System.nanoTime() - start;

            assertNotNull("the bounded queue must eventually refuse a send", full);
            assertTrue(full.getRetryAfterMillis() > 0);
            assertTrue(full.isRetryable());
            assertTrue("refusal must be immediate, took " + elapsed / 1_000_000 + "ms",
                    elapsed < TimeUnit.MILLISECONDS.toNanos(200));
            assertTrue(api.stats().getRejected() > 0);
        } finally {
            api.close();
        }
    }

    @Test
    public void highPriorityAsyncQueueFullCompletesFutureWithTypedError() throws Exception {
        hub.handler(exchange -> {
            sleep(2_000);
            TestServer.respondJson(exchange, 202, sendJson());
        });
        NotifierApi api = new NotifierApi(config()
                .highPriorityQueueCapacity(1)
                .highPrioritySenderThreads(1)
                .highPriorityMaxInFlight(1)
                .closeDrainMillis(50)
                .maxRetries(0)
                .build());
        SendOptions high = SendOptions.builder().priority(SendPriority.HIGH).build();
        try {
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", high, "1");

            ExecutionException queueFull = null;
            for (int i = 0; i < 10 && queueFull == null; i++) {
                CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(
                        ChannelType.SMS, "+989120000000", "otp", high, "2");
                if (future.isCompletedExceptionally()) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        queueFull = e;
                    }
                }
            }
            assertTrue("a full HIGH lane must fail the async future immediately",
                    queueFull != null);
            assertTrue(queueFull.getCause() instanceof NotifierQueueFullException);
            assertTrue(((NotifierQueueFullException) queueFull.getCause()).getRetryAfterMillis() > 0);
            assertTrue(api.stats().getRejected() > 0);
        } finally {
            api.close();
        }
    }

    @Test
    public void highPriorityLaneIsNotStarvedByBulkBacklog() throws Exception {
        hub.handler(exchange -> {
            if ("bulk".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Priority"))) {
                sleep(700);
            }
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                .bulkPrioritySenderThreads(1)
                .bulkMaxInFlight(1)
                .bulkQueueCapacity(10)
                .highPrioritySenderThreads(1)
                .highPriorityMaxInFlight(1)
                .highPriorityQueueCapacity(10)
                .closeDrainMillis(100)
                .build());
        try {
            SendOptions bulk = SendOptions.builder().priority(SendPriority.BULK)
                    .deadline(Duration.ofSeconds(5)).build();
            List<CompletableFuture<SendResult>> bulkFutures = new ArrayList<CompletableFuture<SendResult>>();
            for (int i = 0; i < 5; i++) {
                bulkFutures.add(api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000",
                        "bulk-template", bulk, "x"));
            }
            // Give the single bulk dispatcher enough time to hold the only bulk permit.
            long warm = System.nanoTime();
            while (api.stats().getInFlight() < 1 && System.nanoTime() - warm < TimeUnit.SECONDS.toNanos(2)) {
                Thread.sleep(5);
            }

            long start = System.nanoTime();
            SendResult high = api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000",
                    "vibe:betaauth",
                    SendOptions.builder().priority(SendPriority.HIGH)
                            .deadline(Duration.ofSeconds(2)).build(), "654321")
                    .get(2, TimeUnit.SECONDS);
            long highNanos = System.nanoTime() - start;

            assertNotNull(high.getId());
            assertTrue("HIGH must not wait behind BULK, took " + highNanos / 1_000_000 + "ms",
                    highNanos < TimeUnit.MILLISECONDS.toNanos(500));
            assertTrue("bulk work should still be pending", api.stats().getInFlight() >= 1);
        } finally {
            api.close();
        }
    }

    @Test
    public void rateLimitedResponseCarriesRetryAfterHeader() throws Exception {
        hub.handler(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "2");
            TestServer.respondJson(exchange, 429, "{\"error\":\"slow down\"}");
        });

        NotifierApi api = new NotifierApi(config().maxRetries(0).build());
        try {
            try {
                api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                        .get(3, TimeUnit.SECONDS);
                fail("expected a rate-limit failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof
                        com.softino.notifier.exception.NotifierRateLimitedException);
                assertEquals(2_000,
                        ((com.softino.notifier.exception.NotifierRateLimitedException)
                                expected.getCause()).getRetryAfterMillis());
            }
            assertEquals(1, hub.requestCount());
        } finally {
            api.close();
        }
    }

    @Test
    public void rateLimitedResponseUsesConfiguredFallbackAndRetries() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        hub.handler(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                TestServer.respondJson(exchange, 429, "{\"error\":\"slow down\"}");
            } else {
                TestServer.respondJson(exchange, 202, sendJson());
            }
        });
        final AtomicReference<com.softino.notifier.exception.NotifierException> retryReason =
                new AtomicReference<com.softino.notifier.exception.NotifierException>();

        NotifierApi api = new NotifierApi(config()
                .maxRetries(1)
                .rateLimitedRetryAfterMillis(20)
                .sendListener(new SendListener() {
                    @Override
                    public void onRetry(SendEvent event, int nextAttempt, long delayMillis,
                                        com.softino.notifier.exception.NotifierException reason) {
                        retryReason.set(reason);
                    }
                })
                .build());
        try {
            SendResult result = api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                    .get(3, TimeUnit.SECONDS);
            assertNotNull(result.getId());
            assertEquals(2, calls.get());
            assertEquals(1, api.stats().getRetries());
            assertTrue(retryReason.get() instanceof
                    com.softino.notifier.exception.NotifierRateLimitedException);
            assertEquals(20,
                    ((com.softino.notifier.exception.NotifierRateLimitedException) retryReason.get())
                            .getRetryAfterMillis());
        } finally {
            api.close();
        }
    }

    @Test
    public void deadlineProducesTypedTimeoutWithoutWaitingForReadTimeout() throws Exception {
        hub.handler(exchange -> {
            sleep(2_000);
            TestServer.respondJson(exchange, 202, sendJson());
        });

        NotifierApi api = new NotifierApi(config()
                .asyncReadTimeoutMillis(5_000)
                .maxRetries(0)
                .build());
        try {
            long start = System.nanoTime();
            CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(ChannelType.SMS,
                    "+989120000000", "otp",
                    SendOptions.builder().deadline(Duration.ofMillis(300)).build(), "123456");

            try {
                future.get(3, TimeUnit.SECONDS);
                fail("expected a timeout");
            } catch (ExecutionException expected) {
                assertTrue("expected NotifierTimeoutException, got " + expected.getCause(),
                        expected.getCause() instanceof NotifierTimeoutException);
                assertTrue(((NotifierTimeoutException) expected.getCause()).isRetryable());
            }
            long elapsed = System.nanoTime() - start;
            assertTrue("deadline must cut the wait short, took " + elapsed / 1_000_000 + "ms",
                    elapsed < TimeUnit.MILLISECONDS.toNanos(1_500));
        } finally {
            api.close();
        }
    }

    @Test
    public void statusAndBulkAreAsyncToo() throws Exception {
        hub.handler(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String id = path.substring(path.lastIndexOf('/') + 1);
                TestServer.respondJson(exchange, 200,
                        "{\"id\":\"" + id + "\",\"status\":\"delivered\",\"channel_type\":\"sms\"}");
            } else {
                TestServer.respondJson(exchange, 202,
                        "{\"batch_id\":\"" + UUID.randomUUID() + "\",\"count\":2}");
            }
        });

        NotifierApi api = new NotifierApi(config().build());
        try {
            String id = UUID.randomUUID().toString();
            StatusResult status = api.statusByNotificationIdAsync(id).get(3, TimeUnit.SECONDS);
            assertEquals(id, status.getId());
            assertTrue(status.isDelivered());

            List<RecipientMessage> messages = new ArrayList<RecipientMessage>();
            messages.add(new RecipientMessage.Builder().recipient("+989120000001").body("hi").build());
            messages.add(new RecipientMessage.Builder().recipient("+989120000002").body("hi").build());
            BulkResult bulk = api.bulkAsync(ChannelType.SMS, messages, false).get(3, TimeUnit.SECONDS);
            assertEquals(2, bulk.getCount());
        } finally {
            api.close();
        }
    }

    @Test
    public void optionsAndTemplateOverloadsKeepTracePriorityAndDeadline() throws Exception {
        NotifierApi api = new NotifierApi(config().build());
        try {
            SendOptions options = SendOptions.builder()
                    .traceId("trace-9")
                    .priority(SendPriority.HIGH)
                    .deadline(Duration.ofSeconds(2))
                    .build();
            SendResult result = api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000",
                    "otp", options, "123456").get(3, TimeUnit.SECONDS);
            assertNotNull(result.getIdempotencyKey());

            TestServer.Recorded request = hub.lastRequest();
            assertEquals("high", request.header("X-Priority"));
            assertEquals("trace-9", request.header("X-Trace-Id"));
            assertEquals(result.getIdempotencyKey(), request.header("Idempotency-Key"));
        } finally {
            api.close();
        }
    }

    @Test
    public void throwingListenerCannotBreakDispatch() throws Exception {
        NotifierApi api = new NotifierApi(config()
                .maxRetries(0)
                .sendListener(new SendListener() {
                    @Override
                    public void onAccepted(SendEvent event) {
                        throw new RuntimeException("listener bug");
                    }

                    @Override
                    public void onCompleted(SendEvent event, SendOutcome outcome, long durationMillis,
                                            com.softino.notifier.exception.NotifierException error) {
                        throw new RuntimeException("listener bug");
                    }

                    @Override
                    public void onRetry(SendEvent event, int nextAttempt, long delayMillis,
                                        com.softino.notifier.exception.NotifierException reason) {
                        throw new RuntimeException("listener bug");
                    }

                    @Override
                    public void onRejected(SendEvent event,
                                           com.softino.notifier.exception.NotifierException reason) {
                        throw new RuntimeException("listener bug");
                    }
                })
                .build());
        try {
            SendResult result = api.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                    .get(3, TimeUnit.SECONDS);
            assertNotNull(result.getId());
            assertEquals(1, api.stats().getCompleted());
        } finally {
            api.close();
        }
    }

    @Test
    public void invalidPushDeviceFailsTypedWithoutThrowingFromForget() throws Exception {
        NotifierApi api = new NotifierApi(config().build());
        try {
            // AndForget must not throw a third, caller-visible exception type for a bad device.
            api.sendToDeviceAndForget("not-a-uuid", "hello");
            assertTrue(api.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(1, api.stats().getDropped());

            CompletableFuture<SendResult> failed = api.sendToDeviceAsync("not-a-uuid", "hello");
            try {
                failed.get(3, TimeUnit.SECONDS);
                fail("expected a typed failure for an invalid device id");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof
                        com.softino.notifier.exception.NotifierInvalidRequestException);
            }
            assertEquals(2, api.stats().getAccepted());
            assertEquals(2, api.stats().getFailed());
        } finally {
            api.close();
        }
    }

    @Test
    public void logsAndExceptionMessagesNeverContainOtpOrBody() throws Exception {
        final String otp = "998877";
        final String secretBody = "SECRET-MESSAGE-BODY-42";
        final List<String> logLines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final java.util.logging.Logger sdkLogger =
                java.util.logging.Logger.getLogger("com.softino.notifier");
        final java.util.logging.Level previousLevel = sdkLogger.getLevel();
        final java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logLines.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        sdkLogger.setLevel(java.util.logging.Level.ALL);
        handler.setLevel(java.util.logging.Level.ALL);
        sdkLogger.addHandler(handler);

        hub.handler(exchange -> TestServer.respondJson(exchange, 500,
                "{\"error\":\"" + secretBody + "\",\"code\":5001}"));
        NotifierApi api = new NotifierApi(config().maxRetries(0).build());
        try {
            CompletableFuture<SendResult> future = api.sendTemplateByNameAsync(
                    ChannelType.SMS, "+989120000000", "otp", otp);
            try {
                future.get(3, TimeUnit.SECONDS);
                fail("expected a failure");
            } catch (ExecutionException expected) {
                String message = String.valueOf(expected.getCause().getMessage());
                assertTrue("exception message leaked the body: " + message, !message.contains(secretBody));
                assertTrue("exception message leaked the OTP: " + message, !message.contains(otp));
            }
            api.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", otp);
            api.awaitIdle(Duration.ofSeconds(3));

            for (String line : logLines) {
                assertTrue("log leaked the OTP: " + line, !line.contains(otp));
                assertTrue("log leaked the body: " + line, !line.contains(secretBody));
                assertTrue("log leaked the full recipient: " + line,
                        !line.contains("+989120000000"));
            }
        } finally {
            api.close();
            sdkLogger.removeHandler(handler);
            sdkLogger.setLevel(previousLevel);
        }
    }
}
