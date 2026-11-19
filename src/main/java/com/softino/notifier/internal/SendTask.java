package com.softino.notifier.internal;

import com.softino.notifier.SendEvent;
import com.softino.notifier.SendOptions;
import com.softino.notifier.SendPriority;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One accepted send, from enqueue to terminal outcome. Accessed only by dispatcher, reactor,
 * retry and shutdown paths; never mutated from a caller thread. The mutable fields are
 * volatile because the terminal callback can run on the reactor while {@code close()} aborts
 * from another thread.
 */
final class SendTask<T> {

    final RequestSpec.Factory<T> factory;
    final SendOptions options;
    final SendPriority priority;
    final SendLane lane;
    final CompletableFuture<T> future; // null for fire-and-forget
    final String traceId;
    final boolean generateIdempotencyKey;
    /** Whether this task is a send (counted in send metrics) or a read such as status. */
    final boolean sendOperation;
    final long acceptedNanos;
    final long deadlineNanos;

    final AtomicBoolean settled = new AtomicBoolean();
    final AtomicBoolean permitHeld = new AtomicBoolean();

    private volatile RequestSpec<T> spec;
    volatile Future<?> httpFuture;
    volatile ScheduledFuture<?> retryFuture;
    volatile ScheduledFuture<?> deadlineFuture;
    volatile int attempts;
    /** Set only when a local refusal happened before the task was accepted. */
    volatile com.softino.notifier.exception.NotifierException rejection;

    SendTask(RequestSpec.Factory<T> factory, SendOptions options, SendPriority priority,
             SendLane lane, CompletableFuture<T> future, boolean generateIdempotencyKey,
             boolean sendOperation, long acceptedNanos, long deadlineNanos) {
        this.factory = factory;
        this.options = options;
        this.priority = priority;
        this.lane = lane;
        this.future = future;
        this.traceId = options.getTraceId();
        this.generateIdempotencyKey = generateIdempotencyKey;
        this.sendOperation = sendOperation;
        this.acceptedNanos = acceptedNanos;
        this.deadlineNanos = deadlineNanos;
    }

    /** Builds the request once; retries reuse it so the idempotency key never changes. */
    RequestSpec<T> requestSpec() {
        RequestSpec<T> s = spec;
        if (s != null) return s;
        String key = options.getIdempotencyKey();
        if (key == null && generateIdempotencyKey) {
            key = NotifierIds.newIdempotencyKey();
        }
        s = factory.create(key);
        spec = s;
        return s;
    }

    String resolvedIdempotencyKey() {
        RequestSpec<T> s = spec;
        if (s != null) return s.getIdempotencyKey();
        return options.getIdempotencyKey();
    }

    String resolvedChannelType() {
        RequestSpec<T> s = spec;
        return s == null ? null : s.getChannelType();
    }

    SendEvent event(String notificationId) {
        return new SendEvent(priority, resolvedChannelType(), resolvedIdempotencyKey(),
                traceId, notificationId, attempts);
    }

    boolean hasObserver() {
        return future != null && !options.isFireAndForget();
    }
}
