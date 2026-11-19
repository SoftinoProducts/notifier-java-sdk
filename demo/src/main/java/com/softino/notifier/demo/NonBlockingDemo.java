package com.softino.notifier.demo;

import com.softino.notifier.NotifierApi;
import com.softino.notifier.NotifierConfig;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendPriority;
import com.softino.notifier.kavenegar.KavenegarApi;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runnable demonstration that the application thread no longer pays for the hub round trip.
 *
 * <p>Starts a local stub hub that answers after 1.5s, then compares the blocking
 * {@code verifyLookup} with {@code verifyLookupAndForget} and a 1,000-send burst. No network
 * access or real API key is required.</p>
 *
 * <p>Run from {@code notifier-java-sdk/demo}:</p>
 *
 * <pre>
 * mvn -q compile exec:java
 * </pre>
 */
public final class NonBlockingDemo {

    public static void main(String[] args) throws Exception {
        final AtomicBoolean slow = new AtomicBoolean(true);
        HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hub.setExecutor(Executors.newCachedThreadPool());
        hub.createContext("/v1/notifications", exchange -> {
            // Simulate a slow provider/hub: the SDK must not make the caller wait for this.
            // The burst phase uses a faster hub so the demo finishes promptly.
            sleep(slow.get() ? 1_500 : 20);
            byte[] body = ("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\","
                    + "\"channel_type\":\"sms\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        hub.start();
        String baseUrl = "http://127.0.0.1:" + hub.getAddress().getPort();

        NotifierConfig config = NotifierConfig.builder("demo-key")
                .baseUrl(baseUrl)
                .defaultDeadlineMillis(5_000)
                .maxRetries(0)
                .closeDrainMillis(500)
                .build();
        KavenegarApi api = new KavenegarApi(new NotifierApi(config));

        try {
            System.out.println("Hub answers after 1.5s; the SDK is configured with a 5s deadline.");
            System.out.println();

            // The first async call lazily creates the NIO reactor and dispatcher threads, so
            // warm those up before measuring caller latency (a one-time cost, not per send).
            api.verifyLookupAndForget("+989120000000", "0", "vibe:salerequest");
            api.awaitIdle(Duration.ofSeconds(5));

            // ---- blocking baseline ------------------------------------------------
            long start = System.nanoTime();
            api.verifyLookup("+989120000000", "123456", "vibe:salerequest");
            long blockingMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
            System.out.println("verifyLookup (blocking)        : caller held for "
                    + blockingMicros + " µs");

            // ---- fire-and-forget --------------------------------------------------
            start = System.nanoTime();
            api.verifyLookupAndForget("+989120000000", "123456", "vibe:salerequest",
                    SendOptions.builder()
                            .idempotencyKey("otp:sale-1")
                            .traceId("sale-1")
                            .priority(SendPriority.HIGH)
                            .deadline(Duration.ofSeconds(5))
                            .build());
            long forgetMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
            System.out.println("verifyLookupAndForget          : caller returned in "
                    + forgetMicros + " µs (single sample; see burst p99 below)");
            System.out.println();

            // ---- a burst of 1,000 fire-and-forget sends ---------------------------
            int burst = 1_000;
            slow.set(false);
            long[] returns = new long[burst];
            start = System.nanoTime();
            for (int i = 0; i < burst; i++) {
                long t0 = System.nanoTime();
                api.verifyLookupAndForget("+989120000000", "123456", "vibe:salerequest");
                returns[i] = System.nanoTime() - t0;
            }
            long enqueueMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Arrays.sort(returns);
            System.out.println("Burst of " + burst + " OTP enqueues");
            System.out.println("  total enqueue time           : " + enqueueMillis + " ms");
            System.out.println("  p50 caller return            : "
                    + TimeUnit.NANOSECONDS.toMicros(returns[burst / 2]) + " µs");
            System.out.println("  p99 caller return            : "
                    + TimeUnit.NANOSECONDS.toMicros(returns[(int) (burst * 0.99)]) + " µs");
            System.out.println("  SDK threads                  : " + sdkThreadCount());
            System.out.println("  queue after burst            : " + api.stats());

            boolean idle = api.awaitIdle(Duration.ofSeconds(30));
            System.out.println();
            System.out.println("Drained=" + idle + " stats=" + api.stats());
            System.out.println("Delivery itself still depends on the hub; only the caller wait is gone.");
        } finally {
            api.close();
            hub.stop(0);
        }
    }

    private static int sdkThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("notifier-")) {
                count++;
            }
        }
        return count;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
