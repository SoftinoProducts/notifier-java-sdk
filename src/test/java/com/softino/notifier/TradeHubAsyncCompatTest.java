package com.softino.notifier;

import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.models.SendResult;
import com.softino.notifier.kavenegar.models.StatusResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves the trade-hub migration path: the same Kavenegar facade, but {@code verifyLookup}
 * has non-blocking and fire-and-forget equivalents, with OTP on the HIGH lane by default.
 */
public class TradeHubAsyncCompatTest {

    private TestServer hub;
    private KavenegarApi api;

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
            } else {
                TestServer.respondJson(exchange, 202,
                        "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\","
                                + "\"channel_type\":\"sms\",\"recipient\":\"+989120000000\","
                                + "\"body\":\"rendered\"}");
            }
        });
        NotifierConfig config = NotifierConfig.builder("test-key")
                .baseUrl(hub.baseUrl())
                .closeDrainMillis(100)
                .maxRetries(0)
                .build();
        api = new KavenegarApi(new NotifierApi(config));
    }

    @After
    public void stop() {
        if (api != null) api.close();
        if (hub != null) hub.close();
    }

    @Test
    public void verifyLookupAsyncReturnsKavenegarModelAndUsesHighLane() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<SendResult> future = api.verifyLookupAsync("+989120000000", "123456", "vibe:betaauth");
        long submitNanos = System.nanoTime() - start;
        assertTrue(submitNanos < TimeUnit.MILLISECONDS.toNanos(100));

        SendResult result = future.get(3, TimeUnit.SECONDS);
        assertNotNull(result.getNotificationId());
        assertNotNull(result.getMessage());
        assertEquals("high", hub.lastRequest().header("X-Priority"));
    }

    @Test
    public void verifyLookupAndForgetReturnsImmediatelyAndCarriesOptions() throws Exception {
        long start = System.nanoTime();
        api.verifyLookupAndForget("+989120000000", "654321", "vibe:betaauth",
                SendOptions.builder()
                        .idempotencyKey("otp:sale-1")
                        .traceId("sale-1")
                        .deadline(Duration.ofSeconds(2))
                        .priority(SendPriority.HIGH)
                        .build());
        long elapsed = System.nanoTime() - start;
        assertTrue("forget must not wait for the hub, took " + elapsed / 1_000_000 + "ms",
                elapsed < TimeUnit.MILLISECONDS.toNanos(100));

        assertEquals(1, api.stats().getAccepted());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (api.stats().getCompleted() + api.stats().getFailed() == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, api.stats().getCompleted());
        TestServer.Recorded request = hub.lastRequest();
        assertEquals("otp:sale-1", request.header("Idempotency-Key"));
        assertEquals("sale-1", request.header("X-Trace-Id"));
        assertEquals("high", request.header("X-Priority"));
        assertTrue(request.body.contains("\"idempotency_key\":\"otp:sale-1\""));
    }

    @Test
    public void sendAsyncDefaultsToBulkAndCloseRejectsNewWork() throws Exception {
        SendResult result = api.sendAsync("100085902", "+989120000000", "hello").get(3, TimeUnit.SECONDS);
        assertNotNull(result.getNotificationId());
        assertEquals("bulk", hub.lastRequest().header("X-Priority"));

        api.close();
        try {
            api.verifyLookupAndForget("+989120000000", "1", "vibe:betaauth");
            fail("expected NotifierClosedException");
        } catch (NotifierClosedException expected) {
            // expected
        }
    }

    @Test
    public void namedParamsAndSimulatedModesAreAvailableAsync() throws Exception {
        java.util.List<com.softino.notifier.kavenegar.utils.PairValue> params =
                java.util.Arrays.asList(new com.softino.notifier.kavenegar.utils.PairValue("token", "111"));
        SendResult named = api.verifyLookupAsync("+989120000000", "", "", "", "vibe:betaauth", params)
                .get(5, TimeUnit.SECONDS);
        assertNotNull(named.getNotificationId());
        assertTrue(hub.lastRequest().body.contains("\"template_vars\""));
        assertTrue(hub.lastRequest().body.contains("\"token\":\"111\""));

        SendResult simulated = api.verifyLookupAsync("+989120000000", "222", "vibe:betaauth", Boolean.TRUE)
                .get(5, TimeUnit.SECONDS);
        assertNotNull(simulated.getNotificationId());
        assertTrue(hub.lastRequest().body.contains("\"simulated\":true"));
    }

    @Test
    public void listAndArraySendsHaveAsyncEquivalents() throws Exception {
        java.util.List<SendResult> sent = api.sendArrayAsync("100085902",
                java.util.Arrays.asList("+989120000000", "+989120000001"),
                java.util.Arrays.asList("hello", "world")).get(5, TimeUnit.SECONDS);
        assertEquals(2, sent.size());
        assertEquals(2, hub.requestCount());

        java.util.List<SendResult> sameMessage = api.sendAsync("100085902",
                java.util.Arrays.asList("+989120000002", "+989120000003"),
                "hello").get(5, TimeUnit.SECONDS);
        assertEquals(2, sameMessage.size());
        assertEquals(4, hub.requestCount());
    }

    @Test
    public void arrayForgetQueuesEveryRecipient() throws Exception {
        int before = hub.requestCount();
        api.sendArrayAndForget("100085902",
                java.util.Arrays.asList("+989120000010", "+989120000011"),
                java.util.Arrays.asList("one", "two"),
                SendOptions.builder().traceId("batch-1").build());
        assertTrue(api.awaitIdle(java.time.Duration.ofSeconds(5)));
        assertEquals(before + 2, hub.requestCount());
    }

    @Test
    public void statusByNotificationIdAsyncMapsToKavenegarStatus() throws Exception {
        String id = UUID.randomUUID().toString();
        StatusResult status = api.statusByNotificationIdAsync(id).get(3, TimeUnit.SECONDS);
        assertEquals(id, status.getNotificationId());
        assertEquals("delivery to the handset is still the hub's job",
                com.softino.notifier.kavenegar.enums.MessageStatus.Delivered, status.getStatus());
    }
}
