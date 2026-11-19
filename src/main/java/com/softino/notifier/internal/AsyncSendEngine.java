package com.softino.notifier.internal;

import com.google.gson.JsonObject;
import com.softino.notifier.NotifierConfig;
import com.softino.notifier.NotifierStats;
import com.softino.notifier.SendEvent;
import com.softino.notifier.SendListener;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendOutcome;
import com.softino.notifier.SendPriority;
import com.softino.notifier.SendResult;
import com.softino.notifier.StatusResult;
import com.softino.notifier.exception.NotifierClosedException;
import com.softino.notifier.exception.NotifierException;
import com.softino.notifier.exception.NotifierInvalidRequestException;
import com.softino.notifier.exception.NotifierQueueFullException;
import com.softino.notifier.exception.NotifierRateLimitedException;
import com.softino.notifier.exception.NotifierTimeoutException;
import com.softino.notifier.exception.NotifierUnavailableException;
import org.apache.http.ConnectionClosedException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * The non-blocking send engine.
 *
 * <p>Caller threads only validate local state and {@code offer()} a task into a bounded lane
 * queue; no network I/O, no locks and no waiting happens there. A fixed set of daemon
 * dispatcher threads hands tasks to the Apache HttpAsyncClient NIO reactor, which owns all
 * socket I/O. Retries are scheduled on a timer thread, never by sleeping. Both lane queues and
 * both in-flight quotas (HIGH and BULK) are independent and bounded.</p>
 *
 * <p>Not part of the public API.</p>
 */
public final class AsyncSendEngine implements AutoCloseable {

    private static final boolean PRESENT = Boolean.TRUE;
    private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    private final NotifierConfig config;
    private final SendListener listener;
    private final CloseableHttpAsyncClient httpClient;
    private final ScheduledThreadPoolExecutor retryScheduler;
    private final ScheduledThreadPoolExecutor deadlineScheduler;
    private final ExecutorService highDispatchers;
    private final ExecutorService bulkDispatchers;
    private final SendLane highLane;
    private final SendLane bulkLane;

    private final AtomicBoolean closed = new AtomicBoolean();
    /** Set only after the drain window: dispatchers stop taking work and the transport closes. */
    private final AtomicBoolean shutdown = new AtomicBoolean();

    /** Accepted tasks that have not reached a terminal outcome yet (queue + in flight + retry). */
    private final java.util.concurrent.atomic.AtomicInteger live =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Sends currently dispatched and awaiting a hub response (status reads are excluded). */
    private final java.util.concurrent.atomic.AtomicInteger inFlightSends =
            new java.util.concurrent.atomic.AtomicInteger();

    private final LongAdder accepted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    private final ConcurrentHashMap<SendTask<?>, Boolean> inFlight =
            new ConcurrentHashMap<SendTask<?>, Boolean>();
    private final ConcurrentHashMap<SendTask<?>, Boolean> pendingRetry =
            new ConcurrentHashMap<SendTask<?>, Boolean>();

    public AsyncSendEngine(NotifierConfig config) {
        this.config = config;
        this.listener = config.getSendListener() == null ? SendListener.NOOP : config.getSendListener();

        this.highLane = new SendLane(SendPriority.HIGH, config.getHighQueueCapacity(),
                config.getHighMaxInFlight());
        this.bulkLane = new SendLane(SendPriority.BULK, config.getBulkQueueCapacity(),
                config.getBulkMaxInFlight());

        int totalInFlight = config.getHighMaxInFlight() + config.getBulkMaxInFlight();
        IOReactorConfig ioConfig = IOReactorConfig.custom()
                .setIoThreadCount(config.getIoThreads())
                .setSelectInterval(config.getIoSelectIntervalMs())
                .setConnectTimeout(config.getConnectTimeoutMs())
                .setSoTimeout(config.getReadTimeoutMs())
                .setTcpNoDelay(true)
                .setSoKeepAlive(true)
                .build();

        RequestConfig defaultRequestConfig = RequestConfig.custom()
                .setConnectTimeout(config.getConnectTimeoutMs())
                .setSocketTimeout(config.getReadTimeoutMs())
                .setConnectionRequestTimeout(config.getConnectionRequestTimeoutMs())
                .setExpectContinueEnabled(false)
                .build();

        this.httpClient = HttpAsyncClients.custom()
                .setDefaultIOReactorConfig(ioConfig)
                .setThreadFactory(NotifierThreads.daemon("notifier-reactor-"))
                .setMaxConnTotal(totalInFlight)
                .setMaxConnPerRoute(totalInFlight)
                .setDefaultRequestConfig(defaultRequestConfig)
                .setUserAgent(config.getUserAgent())
                .build();
        this.httpClient.start();

        this.retryScheduler = new ScheduledThreadPoolExecutor(1,
                NotifierThreads.daemon("notifier-retry-"));
        this.retryScheduler.setRemoveOnCancelPolicy(true);
        this.retryScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.retryScheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        this.deadlineScheduler = new ScheduledThreadPoolExecutor(1,
                NotifierThreads.daemon("notifier-deadline-"));
        this.deadlineScheduler.setRemoveOnCancelPolicy(true);
        this.deadlineScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        this.highDispatchers = Executors.newFixedThreadPool(
                config.getHighSenderThreads(), NotifierThreads.daemon("notifier-sender-high-"));
        this.bulkDispatchers = Executors.newFixedThreadPool(
                config.getBulkSenderThreads(), NotifierThreads.daemon("notifier-sender-bulk-"));

        startDispatchers(highDispatchers, highLane);
        startDispatchers(bulkDispatchers, bulkLane);
    }

    // ------------------------------------------------------------------
    // Submission: the only methods a caller thread touches.
    // ------------------------------------------------------------------

    /**
     * Accepts an async send and returns the future its caller will observe. Local refusals are
     * reported through the future, never thrown from the caller thread.
     *
     * @param <T>            the caller-facing result type
     * @param factory        builds the request on a dispatcher thread
     * @param options        the send options (deadline, priority, trace id, ...)
     * @param defaultPriority lane to use when the options do not set one
     * @param generateIdempotencyKey whether to generate a key when the caller did not supply one
     * @return the future completed by an SDK thread
     */
    public <T> CompletableFuture<T> submit(RequestSpec.Factory<T> factory, SendOptions options,
                                           SendPriority defaultPriority, boolean generateIdempotencyKey,
                                           boolean sendOperation) {
        SendPriority priority = options.getPriority() == null ? defaultPriority : options.getPriority();
        SendLane lane = lane(priority);
        long now = System.nanoTime();
        long deadline = deadlineNanos(options, now);
        SendTask<T> task = new SendTask<T>(factory, options, priority, lane, new CompletableFuture<T>(),
                generateIdempotencyKey, sendOperation, now, deadline);

        if (!accept(task)) {
            return task.future;
        }
        return task.future;
    }

    /**
     * Accepts a fire-and-forget send. Only local state failures are thrown
     * ({@link NotifierQueueFullException}, {@link NotifierClosedException}); network failures
     * are counted and logged, never thrown.
     *
     * @param factory        builds the request on a dispatcher thread
     * @param options        the send options (deadline, priority, trace id, ...)
     * @param defaultPriority lane to use when the options do not set one
     * @param generateIdempotencyKey whether to generate a key when the caller did not supply one
     */
    public void submitAndForget(RequestSpec.Factory<?> factory, SendOptions options,
                                SendPriority defaultPriority, boolean generateIdempotencyKey) {
        SendPriority priority = options.getPriority() == null ? defaultPriority : options.getPriority();
        SendLane lane = lane(priority);
        long now = System.nanoTime();
        long deadline = deadlineNanos(options, now);
        SendTask<Object> task = new SendTask<Object>(castFactory(factory), options, priority, lane, null,
                generateIdempotencyKey, true, now, deadline);
        if (!accept(task)) {
            throw task.rejection;
        }
    }

    private long deadlineNanos(SendOptions options, long now) {
        long millis = options.getDeadline() != null
                ? options.getDeadline().toMillis()
                : config.getDefaultDeadlineMs();
        return now + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
    }

    /** @return true when accepted; false after completing/recording the refusal on the task */
    private boolean accept(SendTask<?> task) {
        if (closed.get()) {
            reject(task, new NotifierClosedException("notifier client is closed"));
            return false;
        }
        // Count before the offer so a dispatcher that picks the task immediately cannot finish
        // it (and decrement) before this increment, which would leave live at -1.
        live.incrementAndGet();
        if (!task.lane.queue.offer(task)) {
            live.decrementAndGet();
            reject(task, new NotifierQueueFullException(
                    "send queue for " + task.priority + " is full (capacity "
                            + (task.priority == SendPriority.HIGH
                            ? config.getHighQueueCapacity() : config.getBulkQueueCapacity()) + ")",
                    config.getQueueFullRetryAfterMs()));
            return false;
        }
        // Close may have raced the offer after it passed the closed check. If the task is still
        // queued, remove and refuse it; if a dispatcher already took it, that dispatcher will
        // see shutdown and fail it as closed.
        if (shutdown.get() && task.lane.queue.remove(task)) {
            live.decrementAndGet();
            reject(task, new NotifierClosedException("notifier client is closed"));
            return false;
        }
        if (task.sendOperation) {
            accepted.increment();
        }
        scheduleDeadline(task);
        return true;
    }

    /**
     * Enforces the total deadline even when bytes keep arriving: the socket timeout is reset by
     * every read, so only an independent timer guarantees the caller-side bound. Scheduled on
     * the accepting thread (a local heap insert, no I/O); the timer thread only cancels and
     * settles the task.
     */
    private void scheduleDeadline(final SendTask<?> task) {
        long delayNanos = Math.max(0L, task.deadlineNanos - System.nanoTime());
        try {
            task.deadlineFuture = deadlineScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    onDeadline(task);
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException engineClosing) {
            // close() is responsible for settling accepted work.
        }
    }

    private void onDeadline(SendTask<?> task) {
        if (task.settled.get()) {
            return;
        }
        if (shutdown.get()) {
            terminalFailure(task, new NotifierClosedException("notifier client is closed"), true);
            return;
        }
        Future<?> request = task.httpFuture;
        if (request != null) {
            request.cancel(true);
        }
        terminalFailure(task, new NotifierTimeoutException(
                "send deadline exceeded", null), true);
    }

    private void reject(final SendTask<?> task, final NotifierException reason) {
        task.rejection = reason;
        if (task.sendOperation) {
            rejected.increment();
        }
        if (task.future != null) {
            task.future.completeExceptionally(reason);
        }
        if (!task.sendOperation) {
            return;
        }
        // The caller thread must not run listener code or logging (either could block). Hand
        // the notification to the SDK scheduler; after shutdown there is no one to notify.
        final SendEvent event;
        try {
            event = task.event(null);
        } catch (RuntimeException builderFailure) {
            return; // the future/typed exception has already reached the caller
        }
        try {
            retryScheduler.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.onRejected(event, reason);
                    } catch (Throwable ignored) {
                        // A listener must never break submission.
                    }
                    // Queue-full is expected backpressure, not an error; the counter and the
                    // typed exception carry the signal. Keep the line at FINE to avoid a log
                    // flood during a legitimate burst.
                    NotifierLog.fine("event=send.rejected priority=%s reason=%s idempotencyKey=%s traceId=%s",
                            task.priority, reason.getClass().getSimpleName(),
                            task.resolvedIdempotencyKey(), task.traceId);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // client is shutting down; the typed exception already reached the caller
        }
    }

    private SendLane lane(SendPriority priority) {
        return priority == SendPriority.HIGH ? highLane : bulkLane;
    }

    // ------------------------------------------------------------------
    // Dispatchers
    // ------------------------------------------------------------------

    private void startDispatchers(ExecutorService executor, final SendLane lane) {
        for (int i = 0; i < (lane == highLane ? config.getHighSenderThreads()
                : config.getBulkSenderThreads()); i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    dispatchLoop(lane);
                }
            });
        }
    }

    private void dispatchLoop(SendLane lane) {
        while (!shutdown.get()) {
            SendTask<?> task;
            try {
                task = lane.queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null || task.settled.get()) {
                continue;
            }
            // Track before waiting for a permit so close() can see a task a dispatcher holds.
            inFlight.put(task, PRESENT);
            if (!acquire(lane)) {
                terminalFailure(task, new NotifierClosedException("notifier client is closed"), true);
                continue;
            }
            if (task.sendOperation) {
                inFlightSends.incrementAndGet();
            }
            task.permitHeld.set(true);
            if (task.settled.get() || shutdown.get()) {
                releasePermit(task);
                inFlight.remove(task);
                if (shutdown.get()) {
                    terminalFailure(task, new NotifierClosedException("notifier client is closed"), true);
                }
                continue;
            }
            dispatch(task);
        }
    }

    private boolean acquire(SendLane lane) {
        while (!shutdown.get()) {
            try {
                if (lane.permits.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private <T> void dispatch(final SendTask<T> task) {
        task.attempts++;
        RequestSpec<T> spec;
        try {
            spec = task.requestSpec();
        } catch (NotifierException e) {
            terminalFailure(task, new NotifierInvalidRequestException(
                    e.getMessage() == null ? "send request is invalid" : e.getMessage(), 0), true);
            return;
        } catch (RuntimeException e) {
            terminalFailure(task, new NotifierInvalidRequestException(
                    "send request could not be built", 0), true);
            return;
        }

        if (shutdown.get() || task.settled.get()) {
            terminalFailure(task, new NotifierClosedException("notifier client is closed"), true);
            return;
        }

        long now = System.nanoTime();
        if (now >= task.deadlineNanos) {
            terminalFailure(task, new NotifierTimeoutException(
                    "send deadline elapsed before the request could be dispatched", null), true);
            return;
        }

        notifyAccepted(task);

        HttpRequestBase request;
        try {
            request = buildRequest(task, spec, task.deadlineNanos - now);
        } catch (RuntimeException e) {
            terminalFailure(task, new NotifierInvalidRequestException(
                    "send request could not be encoded", 0, e), true);
            return;
        }

        try {
            task.httpFuture = httpClient.execute(request, new FutureCallback<HttpResponse>() {
                @Override
                public void completed(HttpResponse response) {
                    onResponse(task, spec, response);
                }

                @Override
                public void failed(Exception ex) {
                    onIoFailure(task, ex);
                }

                @Override
                public void cancelled() {
                    onCancelled(task);
                }
            });
        } catch (RuntimeException e) {
            // Typically IllegalStateException after close(); treat as a terminal local failure.
            terminalFailure(task, new NotifierClosedException("notifier transport is closed", e), true);
        }
    }

    private HttpRequestBase buildRequest(SendTask<?> task, RequestSpec<?> spec, long remainingNanos) {
        String url = config.getBaseUrl() + spec.getPath();
        HttpRequestBase request;
        String method = spec.getMethod();
        if ("POST".equals(method)) {
            HttpPost post = new HttpPost(url);
            if (spec.getBody() != null) {
                post.setEntity(new StringEntity(spec.getBody(), ContentType.APPLICATION_JSON));
            }
            request = post;
        } else if ("GET".equals(method)) {
            request = new HttpGet(url);
        } else if ("DELETE".equals(method)) {
            request = new HttpDelete(url);
        } else {
            throw new IllegalArgumentException("unsupported method " + method);
        }

        request.setHeader("Accept", "application/json");
        request.setHeader("X-API-Key", config.getApiKey());
        request.setHeader("User-Agent", config.getUserAgent());
        if ("POST".equals(method)) {
            request.setHeader("X-Priority", task.priority.name().toLowerCase(Locale.ROOT));
        }
        if (spec.getBody() != null) {
            request.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        }
        if (spec.getIdempotencyKey() != null) {
            request.setHeader("Idempotency-Key", spec.getIdempotencyKey());
        }
        if (task.traceId != null) {
            request.setHeader("X-Trace-Id", task.traceId);
        }
        for (Map.Entry<String, String> e : spec.getHeaders().entrySet()) {
            request.setHeader(e.getKey(), e.getValue());
        }

        long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        int connect = (int) Math.min(connectTimeoutMs(task.options), remainingMs);
        int read = (int) Math.min(readTimeoutMs(task.options), remainingMs);
        int connectionRequest = (int) Math.min(config.getConnectionRequestTimeoutMs(), remainingMs);
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(Math.max(1, connect))
                .setSocketTimeout(Math.max(1, read))
                .setConnectionRequestTimeout(Math.max(1, connectionRequest))
                .setExpectContinueEnabled(false)
                .build());
        return request;
    }

    private int connectTimeoutMs(SendOptions options) {
        return options.getConnectTimeout() != null
                ? (int) Math.min(Integer.MAX_VALUE, options.getConnectTimeout().toMillis())
                : config.getConnectTimeoutMs();
    }

    private int readTimeoutMs(SendOptions options) {
        return options.getReadTimeout() != null
                ? (int) Math.min(Integer.MAX_VALUE, options.getReadTimeout().toMillis())
                : config.getReadTimeoutMs();
    }

    // ------------------------------------------------------------------
    // Responses and failures
    // ------------------------------------------------------------------

    private <T> void onResponse(SendTask<T> task, RequestSpec<T> spec, HttpResponse response) {
        try {
            int code = response.getStatusLine().getStatusCode();
            String raw = response.getEntity() == null ? null
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (code >= 200 && code < 300) {
                T value;
                try {
                    value = parse(spec, raw);
                } catch (NotifierException e) {
                    terminalFailure(task, e, true);
                    return;
                } catch (Exception e) {
                    // Never attach the parse exception: its message can contain response bytes.
                    terminalFailure(task, new NotifierUnavailableException(
                            "hub returned an unreadable response", null), true);
                    return;
                }
                terminalSuccess(task, value);
                return;
            }

            Failure failure = failureForStatus(code, raw, response);
            handleFailure(task, failure);
        } catch (Throwable t) {
            terminalFailure(task, new NotifierUnavailableException(
                    "hub response could not be processed", null), true);
        }
    }

    private <T> T parse(RequestSpec<T> spec, String raw) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            if (spec.isAllowEmptyBody()) {
                return spec.getParser().parse(null, spec.getIdempotencyKey());
            }
            throw new NotifierUnavailableException("hub returned an empty response", null);
        }
        JsonObject json = JsonSupport.GSON.fromJson(trimmed, JsonObject.class);
        if (json == null && !spec.isAllowEmptyBody()) {
            throw new NotifierUnavailableException("hub returned an empty response", null);
        }
        return spec.getParser().parse(json, spec.getIdempotencyKey());
    }

    private void onIoFailure(SendTask<?> task, Exception ex) {
        try {
            if (shutdown.get() || closed.get() && isClosedException(ex)) {
                terminalFailure(task, new NotifierClosedException("notifier client is closed", ex), true);
                return;
            }
            handleFailure(task, classifyIoFailure(ex));
        } catch (RuntimeException callbackFailure) {
            // Never let a throwable escape a FutureCallback: Apache would terminate the reactor.
            terminalFailure(task, new NotifierUnavailableException(
                    "send callback failed", null), true);
        }
    }

    private void onCancelled(SendTask<?> task) {
        try {
            NotifierException error = closed.get()
                    ? new NotifierClosedException("send was cancelled because the client closed", null)
                    : new NotifierTimeoutException("send was cancelled before a response arrived", null);
            terminalFailure(task, error, true);
        } catch (RuntimeException callbackFailure) {
            terminalFailure(task, new NotifierUnavailableException(
                    "send callback failed", null), true);
        }
    }

    private Failure failureForStatus(int code, String raw, HttpResponse response) {
        long retryAfter = parseRetryAfter(response);
        if (code == 429) {
            return new Failure(new NotifierRateLimitedException(
                    "hub rate limited the request (HTTP 429)",
                    retryAfter > 0 ? retryAfter : config.getRateLimitedRetryAfterMs()),
                    true, "rate_limited");
        }
        if (code == 408) {
            return new Failure(new NotifierTimeoutException(
                    "hub timed out waiting for the request (HTTP 408)", null),
                    config.isHubSupportsIdempotencyKey(), "server_timeout");
        }
        if (code >= 500) {
            return new Failure(new NotifierUnavailableException(
                    "hub returned HTTP " + code + businessCodeSuffix(raw), null),
                    config.isHubSupportsIdempotencyKey(), "server_error");
        }
        if (code >= 400) {
            return new Failure(new NotifierInvalidRequestException(
                    "hub rejected the request with HTTP " + code + businessCodeSuffix(raw), code),
                    false, "invalid_request");
        }
        return new Failure(new NotifierUnavailableException(
                "hub returned an unexpected HTTP " + code, null), false, "unexpected_status");
    }

    /** Adds only a numeric business code, never the server's free-text error field. */
    private static String businessCodeSuffix(String raw) {
        try {
            JsonObject json = JsonSupport.GSON.fromJson(raw, JsonObject.class);
            if (json != null && json.has("code") && json.get("code").isJsonPrimitive()) {
                return " (code " + json.get("code").getAsInt() + ")";
            }
        } catch (RuntimeException ignored) {
            // no numeric code available
        }
        return "";
    }

    private static long parseRetryAfter(HttpResponse response) {
        Header header = response.getFirstHeader("Retry-After");
        if (header == null || header.getValue() == null) {
            return -1;
        }
        String value = header.getValue().trim();
        try {
            return Math.max(0, Long.parseLong(value) * 1000L);
        } catch (NumberFormatException ignored) {
            // fall through to an HTTP date
        }
        try {
            java.util.Date date = DateUtils.parseDate(value);
            return date == null ? -1 : Math.max(0, date.getTime() - System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private Failure classifyIoFailure(Exception ex) {
        Throwable t = rootCause(ex);
        if (t instanceof org.apache.http.conn.ConnectTimeoutException) {
            return new Failure(new NotifierUnavailableException(
                    "could not connect to the hub before the connect timeout", ex), true, "connect_timeout");
        }
        if (t instanceof UnknownHostException) {
            return new Failure(new NotifierUnavailableException(
                    "hub host could not be resolved", ex), true, "dns");
        }
        if (t instanceof ConnectException) {
            return new Failure(new NotifierUnavailableException(
                    "hub connection was refused", ex), true, "connect_refused");
        }
        if (t instanceof NoRouteToHostException) {
            return new Failure(new NotifierUnavailableException(
                    "hub host is unreachable", ex), true, "unreachable");
        }
        if (t instanceof SSLException) {
            return new Failure(new NotifierUnavailableException(
                    "TLS handshake with the hub failed", ex), true, "tls");
        }
        if (t instanceof SocketTimeoutException) {
            return new Failure(new NotifierTimeoutException(
                    "read timed out waiting for the hub", ex),
                    config.isHubSupportsIdempotencyKey(), "read_timeout");
        }
        if (t instanceof ConnectionClosedException) {
            return new Failure(new NotifierUnavailableException(
                    "hub closed the connection before responding", ex),
                    config.isHubSupportsIdempotencyKey(), "connection_closed");
        }
        if (t instanceof InterruptedIOException) {
            return new Failure(new NotifierTimeoutException(
                    "request was interrupted before a response arrived", ex),
                    config.isHubSupportsIdempotencyKey(), "interrupted");
        }
        if (t instanceof IOException) {
            return new Failure(new NotifierUnavailableException(
                    "transport failure talking to the hub", ex),
                    config.isHubSupportsIdempotencyKey(), "io");
        }
        return new Failure(new NotifierUnavailableException(
                "unexpected transport failure", ex), false, "unknown");
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private void handleFailure(SendTask<?> task, Failure failure) {
        if (task.settled.get()) return;
        NotifierException error = failure.error;
        long now = System.nanoTime();
        if (now >= task.deadlineNanos) {
            terminalFailure(task, new NotifierTimeoutException(
                    "send deadline exceeded", error), true);
            return;
        }

        int maxRetries = task.options.getMaxRetries() == null
                ? config.getMaxRetries() : task.options.getMaxRetries();
        if (failure.safeToRetry && !shutdown.get() && task.attempts <= maxRetries) {
            long delayNanos = retryDelayNanos(task, failure);
            if (now + delayNanos < task.deadlineNanos) {
                if (scheduleRetry(task, delayNanos, error, failure.reason)) {
                    return;
                }
            }
        }
        terminalFailure(task, error, true);
    }

    private long retryDelayNanos(SendTask<?> task, Failure failure) {
        if (failure.retryAfterMillis > 0) {
            return TimeUnit.MILLISECONDS.toNanos(failure.retryAfterMillis);
        }
        long base = config.getRetryBaseDelayMs();
        int shift = Math.max(0, Math.min(20, task.attempts - 1));
        long exponential = base << shift;
        long capped = Math.min(exponential, config.getRetryMaxDelayMs());
        // Full jitter: spread retries so a downed hub is not hit by a synchronized thundering herd.
        return TimeUnit.MILLISECONDS.toNanos(
                ThreadLocalRandom.current().nextLong(1, Math.max(2, capped + 1)));
    }

    private boolean scheduleRetry(final SendTask<?> task, long delayNanos,
                                  final NotifierException error, final String reason) {
        releasePermit(task);
        inFlight.remove(task);
        pendingRetry.put(task, PRESENT);
        final SendEvent event = task.event(null);
        if (task.sendOperation) {
            retries.increment();
            try {
                listener.onRetry(event, task.attempts + 1, TimeUnit.NANOSECONDS.toMillis(delayNanos), error);
            } catch (Throwable ignored) {
                // listener failures are not dispatch failures
            }
        }
        NotifierLog.fine("event=send.retry priority=%s attempt=%s delayMs=%s reason=%s idempotencyKey=%s traceId=%s",
                task.priority, task.attempts + 1, TimeUnit.NANOSECONDS.toMillis(delayNanos), reason,
                task.resolvedIdempotencyKey(), task.traceId);

        ScheduledFuture<?> scheduled;
        try {
            scheduled = retryScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    onRetryTick(task);
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException engineClosing) {
            pendingRetry.remove(task);
            terminalFailure(task, new NotifierClosedException("notifier client is closed", error), true);
            return false;
        }
        task.retryFuture = scheduled;

        if (shutdown.get()) {
            scheduled.cancel(false);
            pendingRetry.remove(task);
            terminalFailure(task, new NotifierClosedException("notifier client is closed", error), true);
            return false;
        }
        return true;
    }

    private void onRetryTick(SendTask<?> task) {
        pendingRetry.remove(task);
        if (task.settled.get()) return;
        if (shutdown.get()) {
            terminalFailure(task, new NotifierClosedException("notifier client is closed"), true);
            return;
        }
        if (System.nanoTime() >= task.deadlineNanos) {
            terminalFailure(task, new NotifierTimeoutException("send deadline exceeded during retry", null), true);
            return;
        }
        if (!task.lane.queue.offer(task)) {
            terminalFailure(task, new NotifierQueueFullException(
                    "send queue for " + task.priority + " is full while retrying",
                    config.getQueueFullRetryAfterMs()), true);
            return;
        }
        // If close() drained the lane after this offer, accept() never saw the task. The drain
        // loop runs again after dispatchers stop, so the task cannot be stranded there.
    }

    // ------------------------------------------------------------------
    // Terminal outcomes
    // ------------------------------------------------------------------

    private <T> void terminalSuccess(SendTask<T> task, T value) {
        if (!task.settled.compareAndSet(false, true)) return;
        releasePermit(task);
        inFlight.remove(task);
        pendingRetry.remove(task);
        if (task.sendOperation) {
            completed.increment();
        }

        try {
            String notificationId = notificationIdOf(value);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - task.acceptedNanos);
            SendEvent event = task.event(notificationId);
            putMdc(event, notificationId);
            // Complete the future first: a listener that (incorrectly) waits on it must not be
            // able to deadlock the reactor thread that is delivering the outcome.
            try {
                if (task.future != null) task.future.complete(value);
            } catch (Throwable ignored) {
                // A dependent stage may throw; the send itself already succeeded.
            }
            if (task.sendOperation) {
                try {
                    listener.onCompleted(event, SendOutcome.SUCCESS, durationMs, null);
                } catch (Throwable ignored) {
                    // observation must never affect dispatch
                }
                NotifierLog.fine("event=send.completed outcome=SUCCESS notificationId=%s priority=%s attempt=%s "
                                + "durationMs=%s idempotencyKey=%s traceId=%s",
                        notificationId, task.priority, task.attempts, durationMs,
                        task.resolvedIdempotencyKey(), task.traceId);
            }
        } catch (Throwable eventFailure) {
            // Last-resort settlement: never leave an accepted future hanging because building
            // the observation event failed.
            if (task.future != null) {
                try {
                    task.future.complete(value);
                } catch (Throwable ignored) {
                    // ignored
                }
            }
        } finally {
            clearMdc();
            cancelTimers(task);
            live.decrementAndGet();
        }
    }

    private void terminalFailure(SendTask<?> task, NotifierException error, boolean countDropped) {
        if (!task.settled.compareAndSet(false, true)) return;
        releasePermit(task);
        inFlight.remove(task);
        pendingRetry.remove(task);
        if (task.sendOperation) {
            failed.increment();
        }

        boolean noObserver = !task.hasObserver();
        if (task.sendOperation && noObserver && countDropped) {
            dropped.increment();
        }

        try {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - task.acceptedNanos);
            SendEvent event = task.event(null);
            SendOutcome outcome = error instanceof NotifierTimeoutException
                    ? SendOutcome.TIMEOUT
                    : (noObserver ? SendOutcome.DROPPED : SendOutcome.FAILURE);
            putMdc(event, null);
            if (task.future != null) {
                try {
                    task.future.completeExceptionally(error);
                } catch (Throwable ignored) {
                    // ignored
                }
            }
            if (task.sendOperation) {
                try {
                    listener.onCompleted(event, outcome, durationMs, error);
                    if (noObserver && countDropped) {
                        listener.onDropped(event, error);
                    }
                } catch (Throwable ignored) {
                    // observation must never affect dispatch
                }
            }
            String logLine = String.format(Locale.ROOT,
                    "event=send.failed outcome=%s priority=%s attempt=%s durationMs=%s error=%s "
                            + "idempotencyKey=%s traceId=%s",
                    outcome, task.priority, task.attempts, durationMs,
                    NotifierLog.safeMessage(error), task.resolvedIdempotencyKey(), task.traceId);
            // A shutdown fails every accepted task at once; one line per task would flood the
            // log. Keep those at FINE; real send failures stay at WARNING.
            if (!task.sendOperation || error instanceof NotifierClosedException) {
                NotifierLog.fine("%s", logLine);
            } else {
                NotifierLog.warn("%s", logLine);
            }
        } catch (Throwable eventFailure) {
            if (task.future != null) {
                try {
                    task.future.completeExceptionally(error);
                } catch (Throwable ignored) {
                    // ignored
                }
            }
        } finally {
            clearMdc();
            cancelTimers(task);
            live.decrementAndGet();
        }
    }

    private static void cancelTimers(SendTask<?> task) {
        ScheduledFuture<?> deadline = task.deadlineFuture;
        if (deadline != null) {
            deadline.cancel(false);
        }
        ScheduledFuture<?> retry = task.retryFuture;
        if (retry != null) {
            retry.cancel(false);
        }
    }

    private void notifyAccepted(SendTask<?> task) {
        if (!task.sendOperation) {
            return;
        }
        final SendEvent event;
        try {
            event = task.event(null);
        } catch (RuntimeException ignored) {
            return;
        }
        putMdc(event, null);
        try {
            try {
                listener.onAccepted(event);
            } catch (Throwable ignored) {
                // ignored
            }
            NotifierLog.fine("event=send.accepted priority=%s attempt=%s idempotencyKey=%s traceId=%s",
                    task.priority, task.attempts, task.resolvedIdempotencyKey(), task.traceId);
        } finally {
            clearMdc();
        }
    }

    private static String notificationIdOf(Object value) {
        if (value instanceof SendResult) {
            return ((SendResult) value).getId();
        }
        if (value instanceof StatusResult) {
            return ((StatusResult) value).getId();
        }
        return null;
    }

    private void releasePermit(SendTask<?> task) {
        if (task.permitHeld.compareAndSet(true, false)) {
            if (task.sendOperation) {
                inFlightSends.decrementAndGet();
            }
            task.lane.permits.release();
        }
    }

    private static void putMdc(SendEvent event, String notificationId) {
        if (event == null) return;
        MdcBridge.put("traceId", event.getTraceId());
        MdcBridge.put("idempotencyKey", event.getIdempotencyKey());
        MdcBridge.put("notificationId", notificationId);
    }

    private static void clearMdc() {
        MdcBridge.remove("traceId");
        MdcBridge.remove("idempotencyKey");
        MdcBridge.remove("notificationId");
    }

    // ------------------------------------------------------------------
    // Close / drain
    // ------------------------------------------------------------------

    /**
     * Stops accepting, drains accepted work for the configured window, then fails anything that
     * is left and releases every thread and connection. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getCloseDrainMs());
        while (!isIdle() && System.nanoTime() < drainDeadline) {
            LockSupport.parkNanos(PARK_NANOS);
        }

        shutdown.set(true);

        // Refuse whatever was queued but not dispatched.
        failQueued(highLane);
        failQueued(bulkLane);
        failPendingRetries();

        highDispatchers.shutdownNow();
        bulkDispatchers.shutdownNow();
        awaitTermination(highDispatchers, 2_000);
        awaitTermination(bulkDispatchers, 2_000);

        // For any task offered by a racing submit/retry after the first drain.
        failQueued(highLane);
        failQueued(bulkLane);
        failPendingRetries();

        // No dispatcher can add to inFlight any more; abort what the transport still holds.
        for (SendTask<?> task : new ArrayList<SendTask<?>>(inFlight.keySet())) {
            abortClosed(task);
        }

        retryScheduler.shutdownNow();
        deadlineScheduler.shutdownNow();
        awaitTermination(retryScheduler, 1_000);
        awaitTermination(deadlineScheduler, 1_000);

        // A retry tick may have offered its task just before the scheduler stopped; no
        // dispatcher is left to take it, so fail it here rather than stranding it.
        failQueued(highLane);
        failQueued(bulkLane);
        failPendingRetries();

        try {
            httpClient.close();
        } catch (IOException ignored) {
            // nothing meaningful to do on close
        }

        for (SendTask<?> task : new ArrayList<SendTask<?>>(inFlight.keySet())) {
            abortClosed(task);
        }

        // Last line of defence: a submit or retry tick that raced the final drain above. No
        // dispatcher or scheduler can produce new work at this point.
        failQueued(highLane);
        failQueued(bulkLane);
        failPendingRetries();
    }

    private void failQueued(SendLane lane) {
        SendTask<?> task;
        while ((task = lane.queue.poll()) != null) {
            terminalFailure(task, new NotifierClosedException("client closed before the send was dispatched"), true);
        }
    }

    private void failPendingRetries() {
        for (SendTask<?> task : new ArrayList<SendTask<?>>(pendingRetry.keySet())) {
            pendingRetry.remove(task);
            if (task.retryFuture != null) {
                task.retryFuture.cancel(false);
            }
            terminalFailure(task, new NotifierClosedException("client closed before the retry could run"), true);
        }
    }

    private void abortClosed(SendTask<?> task) {
        if (task.settled.get()) {
            inFlight.remove(task);
            return;
        }
        Future<?> future = task.httpFuture;
        if (future != null) {
            future.cancel(true);
        }
        terminalFailure(task, new NotifierClosedException("client closed while the send was in flight"), true);
    }

    private static void awaitTermination(ExecutorService executor, long millis) {
        try {
            executor.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    /** True when no accepted work is queued, in flight or waiting to retry. */
    public boolean isIdle() {
        return live.get() == 0
                && pendingRetry.isEmpty()
                && highLane.queue.isEmpty()
                && bulkLane.queue.isEmpty()
                && inFlight.isEmpty();
    }

    /**
     * Parks the calling thread (not a send path) until the engine is idle or the timeout
     * expires. Useful for tests, demos and graceful application shutdown.
     */
    public boolean awaitIdle(java.time.Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        while (System.nanoTime() < deadline) {
            if (isIdle()) return true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return isIdle();
    }

    public NotifierStats stats() {
        return new NotifierStats(accepted.sum(), completed.sum(), failed.sum(), retries.sum(),
                rejected.sum(), dropped.sum(), inFlightSends.get(),
                highLane.queue.size(), bulkLane.queue.size());
    }

    public boolean isClosed() {
        return closed.get();
    }

    @SuppressWarnings("unchecked")
    private static <T> RequestSpec.Factory<T> castFactory(RequestSpec.Factory<?> factory) {
        return (RequestSpec.Factory<T>) factory;
    }

    /** A classified failure with the SDK's retry decision attached. */
    private static final class Failure {
        final NotifierException error;
        final boolean safeToRetry;
        final long retryAfterMillis;
        final String reason;

        Failure(NotifierException error, boolean safeToRetry, String reason) {
            this(error, safeToRetry, reason, -1);
        }

        Failure(NotifierException error, boolean safeToRetry, String reason, long retryAfterMillis) {
            this.error = error;
            this.safeToRetry = safeToRetry;
            this.reason = reason;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    private static boolean isClosedException(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof NotifierClosedException
                    || current instanceof IllegalStateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
