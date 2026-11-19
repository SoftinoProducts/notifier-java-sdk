package com.softino.notifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local before/after benchmark for the non-blocking send path. Not part of {@code mvn test};
 * run it explicitly:
 *
 * <pre>
 * mvn -o -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.softino.notifier.AsyncBenchmark -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Numbers are machine-specific; the checked-in report records the machine and JDK used.</p>
 */
public final class AsyncBenchmark {

    private static final int SEND_COUNT = 10_000;

    private AsyncBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        // Failure logs are intentionally WARNING-level; silence them so the report is readable.
        java.util.logging.Logger sdkLogger =
                java.util.logging.Logger.getLogger("com.softino.notifier");
        sdkLogger.setLevel(java.util.logging.Level.OFF);
        sdkLogger.setUseParentHandlers(false);
        int delayMs = Integer.getInteger("delayMs", 2);
        System.out.println("Notifier Java SDK async benchmark");
        System.out.println("java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " cpus=" + Runtime.getRuntime().availableProcessors());
        System.out.println("hub delay per request: " + delayMs + "ms");
        System.out.println();

        TestServer hub = new TestServer(exchange -> {
            sleep(delayMs);
            TestServer.respondJson(exchange, 202,
                    "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\",\"channel_type\":\"sms\"}");
        });
        try {
            runHealthyHub(hub);
        } finally {
            hub.close();
        }

        TestServer slowHub = new TestServer(exchange -> {
            sleep(5_000);
            TestServer.respondJson(exchange, 202,
                    "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"queued\"}");
        });
        try {
            runSlowHub(slowHub);
        } finally {
            slowHub.close();
        }
    }

    private static void runHealthyHub(TestServer hub) throws Exception {
        System.out.println("### Healthy hub");
        System.out.println();
        System.out.println("| scenario | calls | wall (ms) | p50 return (µs) | p99 return (µs) | SDK threads | heap used (MB) |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");

        // Warm up both transports and JIT.
        NotifierApi warmApi = new NotifierApi("bench-key", hub.baseUrl());
        for (int i = 0; i < 200; i++) {
            warmApi.sendTemplateByName(ChannelType.SMS, "+989120000000", "otp", "123456");
        }
        warmApi.close();

        // Before: blocking, sequential.
        NotifierApi blocking = new NotifierApi("bench-key", hub.baseUrl());
        int blockingCalls = 500;
        long[] blockingLatency = new long[blockingCalls];
        long start = System.nanoTime();
        for (int i = 0; i < blockingCalls; i++) {
            long t0 = System.nanoTime();
            blocking.sendTemplateByName(ChannelType.SMS, "+989120000000", "otp", "123456");
            blockingLatency[i] = System.nanoTime() - t0;
        }
        long wall = System.nanoTime() - start;
        printRow("blocking send, 1 thread", blockingCalls, wall, blockingLatency, blocking);
        blocking.close();

        // Before: blocking, 32 concurrent caller threads (throughput view).
        int concurrentCalls = 2_000;
        int workers = 32;
        ExecutorService callers = Executors.newFixedThreadPool(workers);
        NotifierApi concurrentBlocking = new NotifierApi("bench-key", hub.baseUrl());
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < workers; i++) {
            final int perWorker = concurrentCalls / workers;
            tasks.add(callers.submit(() -> {
                ready.countDown();
                go.await();
                for (int j = 0; j < perWorker; j++) {
                    concurrentBlocking.sendTemplateByName(ChannelType.SMS, "+989120000000", "otp", "123456");
                }
                return null;
            }));
        }
        ready.await();
        start = System.nanoTime();
        go.countDown();
        for (java.util.concurrent.Future<?> task : tasks) {
            task.get(60, TimeUnit.SECONDS);
        }
        wall = System.nanoTime() - start;
        printSimpleRow("blocking send, 32 caller threads", concurrentCalls, wall, concurrentBlocking);
        concurrentBlocking.close();
        callers.shutdownNow();

        // After: non-blocking async, 10k submissions.
        NotifierApi asyncApi = new NotifierApi(asyncConfig(hub.baseUrl()));
        // Warm up engine creation and JIT.
        for (int i = 0; i < 500; i++) {
            asyncApi.sendTemplateByNameAsync(ChannelType.SMS, "+989120000000", "otp", "123456")
                    .get(5, TimeUnit.SECONDS);
        }
        long[] asyncLatency = new long[SEND_COUNT];
        List<CompletableFuture<SendResult>> futures = new ArrayList<CompletableFuture<SendResult>>(SEND_COUNT);
        start = System.nanoTime();
        for (int i = 0; i < SEND_COUNT; i++) {
            long t0 = System.nanoTime();
            futures.add(asyncApi.sendTemplateByNameAsync(
                    ChannelType.SMS, "+989120000000", "otp", "123456"));
            asyncLatency[i] = System.nanoTime() - t0;
        }
        wall = System.nanoTime() - start;
        for (CompletableFuture<SendResult> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        printRow("sendTemplateByNameAsync submit", SEND_COUNT, wall, asyncLatency, asyncApi);
        futures.clear();
        System.gc();

        // After: fire-and-forget, 10k submissions.
        long[] forgetLatency = new long[SEND_COUNT];
        start = System.nanoTime();
        for (int i = 0; i < SEND_COUNT; i++) {
            long t0 = System.nanoTime();
            asyncApi.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");
            forgetLatency[i] = System.nanoTime() - t0;
        }
        wall = System.nanoTime() - start;
        asyncApi.awaitIdle(Duration.ofSeconds(120));
        printRow("sendTemplateByNameAndForget submit", SEND_COUNT, wall, forgetLatency, asyncApi);
        System.out.println();
        System.out.println("async stats: " + asyncApi.stats());
        asyncApi.close();
    }

    private static void runSlowHub(TestServer hub) throws Exception {
        System.out.println();
        System.out.println("### Slow hub (5s response delay)");
        System.out.println();
        System.out.println("| scenario | calls | wall (ms) | p50 return (µs) | p99 return (µs) | SDK threads | partial state |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | --- |");

        // One blocking call shows the old cost directly.
        NotifierApi blocking = new NotifierApi("bench-key", hub.baseUrl());
        long start = System.nanoTime();
        blocking.sendTemplateByName(ChannelType.SMS, "+989120000000", "otp", "123456");
        long wall = System.nanoTime() - start;
        System.out.println(String.format(Locale.ROOT,
                "| blocking send | 1 | %.1f | 5000000 | 5000000 | %d | caller held for the round trip |",
                wall / 1_000_000.0, sdkThreadCount()));
        blocking.close();

        NotifierApi asyncApi = new NotifierApi(asyncConfig(hub.baseUrl()));
        int calls = 300;
        long[] forgetLatency = new long[calls];
        start = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            long t0 = System.nanoTime();
            asyncApi.sendTemplateByNameAndForget(ChannelType.SMS, "+989120000000", "otp", "123456");
            forgetLatency[i] = System.nanoTime() - t0;
        }
        wall = System.nanoTime() - start;
        printRow("sendTemplateByNameAndForget submit", calls, wall, forgetLatency, asyncApi);
        asyncApi.close();
    }

    private static NotifierConfig asyncConfig(String baseUrl) {
        return NotifierConfig.builder("bench-key")
                .baseUrl(baseUrl)
                .bulkQueueCapacity(30_000)
                .highPriorityQueueCapacity(5_000)
                .closeDrainMillis(200)
                .maxRetries(0)
                .build();
    }

    private static void printRow(String scenario, int calls, long wallNanos, long[] latencies,
                                 NotifierApi api) {
        Arrays.sort(latencies);
        long p50 = latencies[(int) (latencies.length * 0.50)];
        long p99 = latencies[Math.min(latencies.length - 1, (int) (latencies.length * 0.99))];
        System.out.println(String.format(Locale.ROOT,
                "| %s | %d | %.1f | %.1f | %.1f | %d | %.1f |",
                scenario, calls, wallNanos / 1_000_000.0,
                p50 / 1_000.0, p99 / 1_000.0,
                sdkThreadCount(), heapUsedMb()));
    }

    private static void printSimpleRow(String scenario, int calls, long wallNanos, NotifierApi api) {
        System.out.println(String.format(Locale.ROOT,
                "| %s | %d | %.1f | n/a | n/a | %d | %.1f |",
                scenario, calls, wallNanos / 1_000_000.0, sdkThreadCount(), heapUsedMb()));
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

    private static double heapUsedMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
