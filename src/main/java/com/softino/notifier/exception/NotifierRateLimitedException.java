package com.softino.notifier.exception;

/**
 * The hub asked the caller to slow down (HTTP 429).
 *
 * <p>Retryable: {@code true}. {@link #getRetryAfterMillis()} carries the hub's
 * {@code Retry-After} value when present, otherwise the configured
 * {@code rateLimitedRetryAfterMillis}.</p>
 */
public class NotifierRateLimitedException extends NotifierException {

    private final long retryAfterMillis;

    public NotifierRateLimitedException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = retryAfterMillis;
    }

    public NotifierRateLimitedException(String message, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.retryAfterMillis = retryAfterMillis;
    }

    /** How long the hub asked the caller to wait before retrying, in milliseconds. */
    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
