package com.softino.notifier;

import java.util.Objects;

/**
 * Immutable configuration for a {@link NotifierApi}, covering both the legacy blocking
 * transport and the non-blocking send path.
 *
 * <p>The two paths are configured independently so that adopting the async API never changes
 * the behaviour of an existing blocking call site:</p>
 *
 * <ul>
 *   <li>{@code blocking*} values feed the synchronous {@code send}/{@code status}/... methods.
 *       Their defaults still match the pre-1.2 SDK (connect 10s, read 30s).</li>
 *   <li>the async values feed {@code *Async}/{@code *AndForget}. Their defaults are the
 *       user-facing ones recommended by the SDK: connect 2s, read 5s, total deadline 10s.</li>
 * </ul>
 *
 * <pre>{@code
 * NotifierApi api = new NotifierApi(NotifierConfig.builder("YOUR-KEY")
 *         .defaultDeadlineMillis(10_000)
 *         .highPriorityQueueCapacity(5_000)
 *         .maxRetries(2)
 *         .closeDrainMillis(5_000)
 *         .build());
 * }</pre>
 */
public final class NotifierConfig {

    // --- async transport defaults -------------------------------------------------
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_DEADLINE_MS = 10_000;

    // --- legacy blocking transport defaults (unchanged from 1.1.x) -----------------
    public static final int DEFAULT_BLOCKING_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_BLOCKING_READ_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_CONNECTIONS = 20;

    // --- queue / lane defaults ----------------------------------------------------
    public static final int DEFAULT_HIGH_QUEUE_CAPACITY = 2_000;
    public static final int DEFAULT_BULK_QUEUE_CAPACITY = 10_000;
    public static final int DEFAULT_HIGH_SENDER_THREADS = 1;
    public static final int DEFAULT_BULK_SENDER_THREADS = 2;
    public static final int DEFAULT_IO_THREADS = 1;
    /**
     * How often the NIO reactor wakes to enforce socket timeouts. Apache HttpAsyncClient 4.x
     * enforces per-request socket timeouts on this tick, so the default keeps the overshoot
     * around 100ms instead of the library's 1000ms default. Lower values cost idle CPU.
     */
    public static final int DEFAULT_IO_SELECT_INTERVAL_MS = 100;
    public static final int DEFAULT_HIGH_MAX_IN_FLIGHT = 8;
    public static final int DEFAULT_BULK_MAX_IN_FLIGHT = 24;

    // --- retry / lifecycle defaults ----------------------------------------------
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final int DEFAULT_RETRY_BASE_DELAY_MS = 200;
    public static final int DEFAULT_RETRY_MAX_DELAY_MS = 2_000;
    public static final int DEFAULT_QUEUE_FULL_RETRY_AFTER_MS = 500;
    public static final int DEFAULT_RATE_LIMITED_RETRY_AFTER_MS = 1_000;
    public static final long DEFAULT_CLOSE_DRAIN_MS = 5_000;

    /** Reported in the {@code User-Agent} header so the hub can identify the SDK version. */
    public static final String DEFAULT_USER_AGENT = "notifier-java-sdk/1.2.0";

    private final String apiKey;
    private final String baseUrl;
    private final boolean allowInsecureBaseUrl;
    private final String userAgent;

    private final int blockingConnectTimeoutMs;
    private final int blockingReadTimeoutMs;
    private final int connectionRequestTimeoutMs;
    private final int maxConnections;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int defaultDeadlineMs;

    private final int highQueueCapacity;
    private final int bulkQueueCapacity;
    private final int highSenderThreads;
    private final int bulkSenderThreads;
    private final int ioThreads;
    private final int ioSelectIntervalMs;
    private final int highMaxInFlight;
    private final int bulkMaxInFlight;

    private final int maxRetries;
    private final int retryBaseDelayMs;
    private final int retryMaxDelayMs;
    private final int queueFullRetryAfterMs;
    private final int rateLimitedRetryAfterMs;
    private final long closeDrainMs;

    private final boolean registerShutdownHook;
    private final boolean hubSupportsIdempotencyKey;
    private final SendListener sendListener;

    private NotifierConfig(Builder b) {
        this.apiKey = b.apiKey;
        this.baseUrl = normalizeBaseUrl(b.baseUrl);
        this.allowInsecureBaseUrl = b.allowInsecureBaseUrl;
        this.userAgent = b.userAgent;

        this.blockingConnectTimeoutMs = b.blockingConnectTimeoutMs;
        this.blockingReadTimeoutMs = b.blockingReadTimeoutMs;
        this.connectionRequestTimeoutMs = b.connectionRequestTimeoutMs;
        this.maxConnections = b.maxConnections;

        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.defaultDeadlineMs = b.defaultDeadlineMs;

        this.highQueueCapacity = b.highQueueCapacity;
        this.bulkQueueCapacity = b.bulkQueueCapacity;
        this.highSenderThreads = b.highSenderThreads;
        this.bulkSenderThreads = b.bulkSenderThreads;
        this.ioThreads = b.ioThreads;
        this.ioSelectIntervalMs = b.ioSelectIntervalMs;
        this.highMaxInFlight = b.highMaxInFlight;
        this.bulkMaxInFlight = b.bulkMaxInFlight;

        this.maxRetries = b.maxRetries;
        this.retryBaseDelayMs = b.retryBaseDelayMs;
        this.retryMaxDelayMs = b.retryMaxDelayMs;
        this.queueFullRetryAfterMs = b.queueFullRetryAfterMs;
        this.rateLimitedRetryAfterMs = b.rateLimitedRetryAfterMs;
        this.closeDrainMs = b.closeDrainMs;

        this.registerShutdownHook = b.registerShutdownHook;
        this.hubSupportsIdempotencyKey = b.hubSupportsIdempotencyKey;
        this.sendListener = b.sendListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts a builder for the mandatory API key. */
    public static Builder builder(String apiKey) {
        return new Builder().apiKey(apiKey);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String base = baseUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public boolean isAllowInsecureBaseUrl() { return allowInsecureBaseUrl; }
    public String getUserAgent() { return userAgent; }

    public int getBlockingConnectTimeoutMs() { return blockingConnectTimeoutMs; }
    public int getBlockingReadTimeoutMs() { return blockingReadTimeoutMs; }
    public int getConnectionRequestTimeoutMs() { return connectionRequestTimeoutMs; }
    public int getMaxConnections() { return maxConnections; }

    /** TCP connect timeout for an async attempt, capped by the remaining deadline. */
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    /** Read/socket timeout for an async attempt, capped by the remaining deadline. */
    public int getReadTimeoutMs() { return readTimeoutMs; }
    /** Default total send deadline (connect + write + read + retries), in milliseconds. */
    public int getDefaultDeadlineMs() { return defaultDeadlineMs; }

    public int getHighQueueCapacity() { return highQueueCapacity; }
    public int getBulkQueueCapacity() { return bulkQueueCapacity; }
    public int getHighSenderThreads() { return highSenderThreads; }
    public int getBulkSenderThreads() { return bulkSenderThreads; }
    public int getIoThreads() { return ioThreads; }
    public int getIoSelectIntervalMs() { return ioSelectIntervalMs; }
    public int getHighMaxInFlight() { return highMaxInFlight; }
    public int getBulkMaxInFlight() { return bulkMaxInFlight; }

    /** Total dispatcher threads owned by the async path (I/O and retry threads are extra). */
    public int getSenderThreadCount() { return highSenderThreads + bulkSenderThreads; }

    public int getMaxRetries() { return maxRetries; }
    public int getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public int getRetryMaxDelayMs() { return retryMaxDelayMs; }
    public int getQueueFullRetryAfterMs() { return queueFullRetryAfterMs; }
    /** Fallback when a 429 carries no usable {@code Retry-After} header. */
    public int getRateLimitedRetryAfterMs() { return rateLimitedRetryAfterMs; }
    public long getCloseDrainMs() { return closeDrainMs; }

    public boolean isRegisterShutdownHook() { return registerShutdownHook; }

    /**
     * Whether the hub honours {@code Idempotency-Key} on {@code POST /v1/notifications}.
     *
     * <p>Defaults to {@code false} because retrying an ambiguous failure (read timeout,
     * connection reset after the request was written, 5xx) can duplicate a message unless the
     * hub de-duplicates it. With the flag off, the SDK only retries failures that are known to
     * happen before the request could reach the hub. Turn it on once the hub team ships
     * idempotency support and the SDK sends the same key on every attempt.</p>
     */
    public boolean isHubSupportsIdempotencyKey() { return hubSupportsIdempotencyKey; }

    public SendListener getSendListener() { return sendListener; }

    /** A builder with the legacy blocking timeouts overridden; used by old constructors. */
    public static Builder legacy(String apiKey, String baseUrl, int connectTimeoutMs, int socketTimeoutMs,
                                 int maxConnectionsPerRoute, boolean allowInsecureBaseUrl) {
        return builder(apiKey)
                .baseUrl(baseUrl)
                .blockingConnectTimeoutMillis(connectTimeoutMs)
                .blockingReadTimeoutMillis(socketTimeoutMs)
                .maxConnections(maxConnectionsPerRoute)
                .allowInsecureBaseUrl(allowInsecureBaseUrl);
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl = NotifierApi.DEFAULT_BASE_URL;
        private boolean allowInsecureBaseUrl;
        private String userAgent = DEFAULT_USER_AGENT;

        private int blockingConnectTimeoutMs = DEFAULT_BLOCKING_CONNECT_TIMEOUT_MS;
        private int blockingReadTimeoutMs = DEFAULT_BLOCKING_READ_TIMEOUT_MS;
        private int connectionRequestTimeoutMs = DEFAULT_CONNECTION_REQUEST_TIMEOUT_MS;
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;

        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        private int defaultDeadlineMs = DEFAULT_DEADLINE_MS;

        private int highQueueCapacity = DEFAULT_HIGH_QUEUE_CAPACITY;
        private int bulkQueueCapacity = DEFAULT_BULK_QUEUE_CAPACITY;
        private int highSenderThreads = DEFAULT_HIGH_SENDER_THREADS;
        private int bulkSenderThreads = DEFAULT_BULK_SENDER_THREADS;
        private int ioThreads = DEFAULT_IO_THREADS;
        private int ioSelectIntervalMs = DEFAULT_IO_SELECT_INTERVAL_MS;
        private int highMaxInFlight = DEFAULT_HIGH_MAX_IN_FLIGHT;
        private int bulkMaxInFlight = DEFAULT_BULK_MAX_IN_FLIGHT;

        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int retryBaseDelayMs = DEFAULT_RETRY_BASE_DELAY_MS;
        private int retryMaxDelayMs = DEFAULT_RETRY_MAX_DELAY_MS;
        private int queueFullRetryAfterMs = DEFAULT_QUEUE_FULL_RETRY_AFTER_MS;
        private int rateLimitedRetryAfterMs = DEFAULT_RATE_LIMITED_RETRY_AFTER_MS;
        private long closeDrainMs = DEFAULT_CLOSE_DRAIN_MS;

        private boolean registerShutdownHook;
        private boolean hubSupportsIdempotencyKey;
        private SendListener sendListener;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }

        /** Base URL without a trailing slash, e.g. {@code https://notifier-api.vibe.ir}. */
        public Builder baseUrl(String baseUrl) { this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl"); return this; }

        /** Permit a plaintext remote base URL; the API key would travel in cleartext. */
        public Builder allowInsecureBaseUrl(boolean allow) { this.allowInsecureBaseUrl = allow; return this; }

        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
            return this;
        }

        /** Connect timeout of the legacy blocking transport (default 10s). */
        public Builder blockingConnectTimeoutMillis(int ms) { this.blockingConnectTimeoutMs = ms; return this; }

        /** Read timeout of the legacy blocking transport (default 30s). */
        public Builder blockingReadTimeoutMillis(int ms) { this.blockingReadTimeoutMs = ms; return this; }

        public Builder connectionRequestTimeoutMillis(int ms) { this.connectionRequestTimeoutMs = ms; return this; }

        /** Connection-pool size of the legacy blocking transport (default 20). */
        public Builder maxConnections(int maxConnections) { this.maxConnections = maxConnections; return this; }

        /** TCP connect timeout of an async attempt (default 2s). */
        public Builder asyncConnectTimeoutMillis(int ms) { this.connectTimeoutMs = ms; return this; }

        /** Read/socket timeout of an async attempt (default 5s). */
        public Builder asyncReadTimeoutMillis(int ms) { this.readTimeoutMs = ms; return this; }

        /** Default total send deadline, including retries (default 10s). */
        public Builder defaultDeadlineMillis(int ms) { this.defaultDeadlineMs = ms; return this; }

        public Builder highPriorityQueueCapacity(int capacity) { this.highQueueCapacity = capacity; return this; }

        public Builder bulkQueueCapacity(int capacity) { this.bulkQueueCapacity = capacity; return this; }

        public Builder highPrioritySenderThreads(int threads) { this.highSenderThreads = threads; return this; }

        public Builder bulkPrioritySenderThreads(int threads) { this.bulkSenderThreads = threads; return this; }

        /**
         * NIO reactor worker threads (default 1), not per-message. Apache starts one additional
         * dispatch thread for the reactor, so the SDK has two {@code notifier-reactor-*} threads
         * at this default.
         */
        public Builder ioThreads(int threads) { this.ioThreads = threads; return this; }

        /**
         * Reactor wake-up interval used to enforce socket timeouts (default 100ms). Apache
         * HttpAsyncClient 4.x checks timeouts on this tick; a lower value tightens the overshoot
         * past a configured read timeout at the cost of idle CPU.
         */
        public Builder ioSelectIntervalMillis(int millis) { this.ioSelectIntervalMs = millis; return this; }

        /** Concurrent HTTP requests reserved for the HIGH lane (default 8). */
        public Builder highPriorityMaxInFlight(int max) { this.highMaxInFlight = max; return this; }

        /** Concurrent HTTP requests allowed on the BULK lane (default 24). */
        public Builder bulkMaxInFlight(int max) { this.bulkMaxInFlight = max; return this; }

        /** Retries after the first attempt (default 2, so up to 3 attempts). */
        public Builder maxRetries(int retries) { this.maxRetries = retries; return this; }

        public Builder retryBaseDelayMillis(int ms) { this.retryBaseDelayMs = ms; return this; }

        public Builder retryMaxDelayMillis(int ms) { this.retryMaxDelayMs = ms; return this; }

        /** Value returned in {@code NotifierQueueFullException.retryAfterMillis}. */
        public Builder queueFullRetryAfterMillis(int ms) { this.queueFullRetryAfterMs = ms; return this; }

        /** Fallback wait when a 429 response has no usable {@code Retry-After} header. */
        public Builder rateLimitedRetryAfterMillis(int ms) { this.rateLimitedRetryAfterMs = ms; return this; }

        /** How long {@code close()} drains accepted work before failing the rest (default 5s). */
        public Builder closeDrainMillis(long ms) { this.closeDrainMs = ms; return this; }

        /** Register a JVM shutdown hook that closes the client. Off by default. */
        public Builder registerShutdownHook(boolean register) { this.registerShutdownHook = register; return this; }

        /**
         * Declare that the hub honours {@code Idempotency-Key}, enabling retries of ambiguous
         * failures. Leave off until the hub ships support; see
         * {@link NotifierConfig#isHubSupportsIdempotencyKey()}.
         */
        public Builder hubSupportsIdempotencyKey(boolean supported) {
            this.hubSupportsIdempotencyKey = supported;
            return this;
        }

        /** Optional observation SPI; see {@link SendListener}. */
        public Builder sendListener(SendListener sendListener) {
            this.sendListener = sendListener;
            return this;
        }

        public NotifierConfig build() {
            Objects.requireNonNull(apiKey, "apiKey");
            Objects.requireNonNull(baseUrl, "baseUrl");
            // Legacy blocking values keep Apache's "0 = infinite/unlimited" convention; the old
            // constructors accepted 0 and must keep doing so.
            requireNonNegative("blockingConnectTimeoutMs", blockingConnectTimeoutMs);
            requireNonNegative("blockingReadTimeoutMs", blockingReadTimeoutMs);
            requireNonNegative("connectionRequestTimeoutMs", connectionRequestTimeoutMs);
            // Apache's pool rejects 0 as well, and so did the 1.1.x constructor.
            requirePositive("maxConnections", maxConnections);
            requirePositive("asyncConnectTimeoutMs", connectTimeoutMs);
            requirePositive("asyncReadTimeoutMs", readTimeoutMs);
            requirePositive("defaultDeadlineMs", defaultDeadlineMs);
            requirePositive("highPriorityQueueCapacity", highQueueCapacity);
            requirePositive("bulkQueueCapacity", bulkQueueCapacity);
            requirePositive("highPrioritySenderThreads", highSenderThreads);
            requirePositive("bulkPrioritySenderThreads", bulkSenderThreads);
            requirePositive("ioThreads", ioThreads);
            requirePositive("ioSelectIntervalMs", ioSelectIntervalMs);
            requirePositive("highPriorityMaxInFlight", highMaxInFlight);
            requirePositive("bulkMaxInFlight", bulkMaxInFlight);
            requireNonNegative("maxRetries", maxRetries);
            requirePositive("retryBaseDelayMs", retryBaseDelayMs);
            requirePositive("retryMaxDelayMs", retryMaxDelayMs);
            requirePositive("queueFullRetryAfterMs", queueFullRetryAfterMs);
            requirePositive("rateLimitedRetryAfterMs", rateLimitedRetryAfterMs);
            requireNonNegative("closeDrainMs", closeDrainMs);
            return new NotifierConfig(this);
        }

        private static void requirePositive(String name, long value) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be > 0, got " + value);
            }
        }

        private static void requireNonNegative(String name, long value) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be >= 0, got " + value);
            }
        }
    }
}
