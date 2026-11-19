package com.softino.notifier.exception;

/**
 * The hub could not be reached, or answered with a server-side failure (5xx).
 *
 * <p>Retryable: {@code true}. A connection-level failure (DNS, connect timeout, refused
 * connection, TLS handshake) is safe to retry because the request never reached the hub. A 5xx
 * is only retried automatically when idempotency support is declared; otherwise the SDK
 * surfaces it and the caller decides.</p>
 */
public class NotifierUnavailableException extends NotifierException {

    public NotifierUnavailableException(String message) {
        super(message);
    }

    public NotifierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
