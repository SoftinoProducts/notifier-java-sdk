package com.softino.notifier.exception;

/**
 * The SDK's bounded queue for a priority lane is full. The message was <strong>not</strong>
 * accepted and was not sent.
 *
 * <p>Retryable: {@code true}. {@link #getRetryAfterMillis()} suggests how long to wait before
 * submitting again. The caller thread is never blocked to make room — the exception is the
 * backpressure signal.</p>
 */
public class NotifierQueueFullException extends NotifierException {

    private final long retryAfterMillis;

    public NotifierQueueFullException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = retryAfterMillis;
    }

    /** Suggested wait before resubmitting, in milliseconds. */
    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
