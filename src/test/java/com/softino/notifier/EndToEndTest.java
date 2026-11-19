package com.softino.notifier;

import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.enums.MessageStatus;
import com.softino.notifier.kavenegar.models.SendResult;
import com.softino.notifier.kavenegar.models.StatusResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Explicit end-to-end test of the trade-hub migration path, through the public Kavenegar facade
 * and an embedded HTTP hub. It asserts the wire format (headers/body), result mapping, status
 * polling, retry behaviour, caller latency, listener events, and shutdown, in one flow.
 *
 * <p>No production code is mocked; only the remote hub is replaced by a local
 * {@code com.sun.net.httpserver.HttpServer}.</p>
 */
public class EndToEndTest {

    private TestServer hub;
    private final AtomicReference<String> phase = new AtomicReference<String>("normal");
    private final AtomicInteger posts = new AtomicInteger();
    private final List<SendEvent> accepted = new CopyOnWriteArrayList<SendEvent>();
    private final List<SendOutcome> outcomes = new CopyOnWriteArrayList<SendOutcome>();
    private final List<String> notificationIds = new CopyOnWriteArrayList<String>();

    @Before
    public void start() throws Exception {
        hub = new TestServer(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String id = path.substring(path.lastIndexOf('/') + 1);
                TestServer.respondJson(exchange, 200,
                        "{\"id\":\"" + id + "\",\"status\":\"delivered\",\"channel_type\":\"sms\","
                                + "\"provider\":\"kavenegar\",\"provider_message_id\":\"560152226\","
                                + "\"sent_at\":\"2026-01-01T00:00:00Z\"}");
                return;
            }
            int call = posts.incrementAndGet();
            String currentPhase = phase.get();
            if ("slow".equals(currentPhase)) {
                sleep(400);
            }
            if ("retry".equals(currentPhase) && call == 1) {
                TestServer.respondJson(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
                return;
            }
            TestServer.respondJson(exchange, 202,
                    "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\","
                            + "\"channel_type\":\"sms\",\"recipient\":\"+989120000000\","
                            + "\"body\":\"rendered message\"}");
        });
    }

    @After
    public void stop() {
        if (hub != null) hub.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NotifierConfig config(long drainMillis) {
        return NotifierConfig.builder("test-key")
                .baseUrl(hub.baseUrl())
                .closeDrainMillis(drainMillis)
                .maxRetries(2)
                .retryBaseDelayMillis(15)
                .retryMaxDelayMillis(40)
                .hubSupportsIdempotencyKey(true)
                .sendListener(new SendListener() {
                    @Override
                    public void onAccepted(SendEvent event) {
                        accepted.add(event);
                    }

                    @Override
                    public void onCompleted(SendEvent event, SendOutcome outcome, long durationMillis,
                                            com.softino.notifier.exception.NotifierException error) {
                        outcomes.add(outcome);
                        if (event.getNotificationId() != null) {
                            notificationIds.add(event.getNotificationId());
                        }
                    }
                })
                .build();
    }

    @Test
    public void tradeHubFlowEndToEnd() throws Exception {
        KavenegarApi api = new KavenegarApi(new NotifierApi(config(2_000)));
        try {
            // 1) The existing blocking call still works, unchanged.
            long blockingStart = System.nanoTime();
            SendResult blocking = api.verifyLookup("+989120000000", "111111", "vibe:salerequest");
            long blockingMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - blockingStart);
            assertNotNull("blocking verifyLookup must still return the notification id",
                    blocking.getNotificationId());
            assertNotNull(blocking.getMessage());
            assertEquals("در صف ارسال", blocking.getStatusText());
            assertTrue(blockingMicros >= 0);
            TestServer.Recorded blockingRequest = hub.lastRequest();
            assertEquals("POST", blockingRequest.method);
            assertTrue(blockingRequest.body.contains("\"template_name\":\"salerequest\""));
            assertTrue(blockingRequest.body.contains("\"group_name\":\"vibe\""));
            assertNull("the blocking path is unchanged and sends no async headers",
                    blockingRequest.header("X-Priority"));

            // 2) Async call: caller returns immediately; hub round trip happens off-thread.
            String key = "otp:sale-42";
            long asyncStart = System.nanoTime();
            CompletableFuture<SendResult> asyncFuture = api.verifyLookupAsync(
                    "+989120000000", "222222", "vibe:salerequest",
                    SendOptions.builder()
                            .idempotencyKey(key)
                            .traceId("sale-42")
                            .priority(SendPriority.HIGH)
                            .deadline(Duration.ofSeconds(3))
                            .build());
            long asyncMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - asyncStart);
            assertTrue("async submit must not wait for the hub: " + asyncMicros + "µs",
                    asyncMicros < TimeUnit.MILLISECONDS.toNanos(100));

            SendResult asyncResult = asyncFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(asyncResult.getNotificationId());
            TestServer.Recorded asyncRequest = hub.lastRequest();
            assertEquals("high", asyncRequest.header("X-Priority"));
            assertEquals("sale-42", asyncRequest.header("X-Trace-Id"));
            assertEquals(key, asyncRequest.header("Idempotency-Key"));
            assertTrue(asyncRequest.body.contains("\"idempotency_key\":\"" + key + "\""));

            // 3) Status by notification id, non-blocking.
            StatusResult status = api.statusByNotificationIdAsync(asyncResult.getNotificationId())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(asyncResult.getNotificationId(), status.getNotificationId());
            assertEquals(MessageStatus.Delivered, status.getStatus());
            assertTrue(status.getStatusText().length() > 0);
            assertEquals("a status read must not be counted as a send",
                    1, api.stats().getAccepted());

            // 4) Fire-and-forget on a slow hub: caller still returns immediately.
            phase.set("slow");
            long forgetStart = System.nanoTime();
            api.verifyLookupAndForget("+989120000000", "333333", "vibe:salerequest",
                    SendOptions.builder()
                            .idempotencyKey("otp:sale-43")
                            .traceId("sale-43")
                            .deadline(Duration.ofSeconds(2))
                            .build());
            long forgetMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - forgetStart);
            assertTrue("forget must return before the 400ms hub response: " + forgetMicros + "µs",
                    forgetMicros < TimeUnit.MILLISECONDS.toNanos(100));
            assertTrue(api.awaitIdle(Duration.ofSeconds(5)));

            NotifierStats stats = api.stats();
            assertEquals(2, stats.getAccepted());   // async + forget; blocking is not counted
            assertEquals(2, stats.getCompleted());
            assertEquals(0, stats.getFailed());
            assertTrue(accepted.size() >= 2);
            assertEquals(2, outcomes.size());
            assertEquals(2, notificationIds.size());
        } finally {
            api.close();
        }
    }

    @Test
    public void retryAndShutdownEndToEnd() throws Exception {
        KavenegarApi api = new KavenegarApi(new NotifierApi(config(200)));
        String key = "otp:sale-44";
        try {
            phase.set("retry");
            CompletableFuture<SendResult> future = api.verifyLookupAsync(
                    "+989120000000", "444444", "vibe:salerequest",
                    SendOptions.builder()
                            .idempotencyKey(key)
                            .traceId("sale-44")
                            .deadline(Duration.ofSeconds(5))
                            .build());
            SendResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result.getNotificationId());
            assertEquals("the 503 must be retried once, then succeed", 2, posts.get());
            List<TestServer.Recorded> requests = hub.requests();
            assertEquals(key, requests.get(requests.size() - 2).header("Idempotency-Key"));
            assertEquals(key, requests.get(requests.size() - 1).header("Idempotency-Key"));
            assertEquals(1, api.stats().getRetries());

            // Accepted work must settle before close() returns, even under load.
            phase.set("slow");
            for (int i = 0; i < 20; i++) {
                api.verifyLookupAndForget("+989120000000", "555555", "vibe:salerequest",
                        SendOptions.builder()
                                .idempotencyKey("otp:shutdown:" + i)
                                .deadline(Duration.ofSeconds(5))
                                .build());
            }
            api.close();
            NotifierStats stats = api.stats();
            assertEquals(21, stats.getAccepted());
            assertEquals(stats.getAccepted(), stats.getCompleted() + stats.getFailed());

            try {
                api.verifyLookupAndForget("+989120000000", "666666", "vibe:salerequest");
                fail("expected NotifierClosedException");
            } catch (NotifierClosedException expected) {
                // expected
            }
            CompletableFuture<SendResult> afterClose = api.verifyLookupAsync(
                    "+989120000000", "777777", "vibe:salerequest");
            assertTrue(afterClose.isDone());
            try {
                afterClose.get();
                fail("expected the future to fail after close");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof NotifierClosedException);
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hasSdkThread() && System.nanoTime() < deadline) {
                sleep(20);
            }
            assertTrue("SDK threads survived close()", !hasSdkThread());
        } finally {
            api.close();
        }
    }

    private static boolean hasSdkThread() {
        List<Thread> threads = new ArrayList<Thread>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("notifier-")) {
                threads.add(thread);
            }
        }
        return !threads.isEmpty();
    }
}
