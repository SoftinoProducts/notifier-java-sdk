package com.softino.notifier.exception;

/**
 * Base unchecked exception for the Notifier SDK. All SDK errors extend this so callers can
 * catch a single type.
 *
 * <p>{@link #isRetryable()} tells a caller whether retrying the same logical send could
 * succeed. It is a hint from the SDK's own retry policy, not a promise: a retry of an
 * ambiguous failure is only safe when the hub de-duplicates on {@code Idempotency-Key} (see
 * {@code NotifierConfig.isHubSupportsIdempotencyKey()}).</p>
 */
public class NotifierException extends RuntimeException {

    public NotifierException(String message) {
        super(message);
    }

    public NotifierException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Whether the failure is transient. The base class defaults to {@code false}; the typed
     * subclasses override it.
     */
    public boolean isRetryable() {
        return false;
    }
}
