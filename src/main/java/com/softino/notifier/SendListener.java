package com.softino.notifier;

/**
 * Optional observation SPI for the asynchronous send path. It is the Micrometer-free way to
 * consume the same signals as the built-in counters and timer.
 *
 * <p>Register one with {@link NotifierConfig.Builder#sendListener(SendListener)}. If Micrometer
 * is on the classpath, {@code com.softino.notifier.metrics.MicrometerSendListener} can be
 * registered as the listener instead to publish the standard metric names.</p>
 *
 * <h2>Threading contract</h2>
 *
 * <p>Callbacks run on SDK-owned daemon threads ({@code notifier-sender-*},
 * {@code notifier-reactor-*}, {@code notifier-retry-*} or {@code notifier-deadline-*}), never on
 * the thread that submitted the send. They must be fast and must not block: a slow listener
 * delays the reactor and can therefore reduce throughput. Do not call {@code NotifierApi.close()}
 * from a callback; close is re-entrant and may wait for the callback that invoked it. Exceptions
 * thrown by a listener are caught and ignored so a listener can never break message dispatch.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Callbacks may run concurrently and on different threads; implementations must be
 * thread-safe.</p>
 */
public interface SendListener {

    /** A no-op listener, useful as a default. */
    SendListener NOOP = new SendListener() { };

    /**
     * The send was accepted into a lane and an attempt is about to start.
     *
     * <p>Called on an SDK dispatcher thread. {@link SendEvent#getIdempotencyKey()} is populated
     * when the key was generated before dispatch; a key generated lazily for a send is visible
     * on {@link #onCompleted} instead.</p>
     */
    default void onAccepted(SendEvent event) {
    }

    /**
     * A terminal outcome was reached.
     *
     * @param event         the send's identifiers and lane
     * @param outcome       {@code SUCCESS}, {@code FAILURE}, {@code TIMEOUT} or {@code DROPPED}
     * @param durationMillis how long the send took, from enqueue to terminal outcome
     * @param error         the typed failure, or {@code null} on success
     */
    default void onCompleted(SendEvent event, SendOutcome outcome, long durationMillis,
                             com.softino.notifier.exception.NotifierException error) {
    }

    /** A retry was scheduled after a failure that is safe and useful to retry. */
    default void onRetry(SendEvent event, int nextAttempt, long delayMillis,
                         com.softino.notifier.exception.NotifierException reason) {
    }

    /** The SDK refused the send locally (queue full or client closed); nothing was sent. */
    default void onRejected(SendEvent event, com.softino.notifier.exception.NotifierException reason) {
    }

    /**
     * An accepted send was lost without an observer: either it was fire-and-forget, or it was
     * still queued/in-flight when {@code close()} stopped waiting.
     */
    default void onDropped(SendEvent event, com.softino.notifier.exception.NotifierException reason) {
    }
}
