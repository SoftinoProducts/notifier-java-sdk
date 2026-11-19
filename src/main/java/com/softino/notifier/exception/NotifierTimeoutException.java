package com.softino.notifier.exception;

/**
 * A send exceeded its total deadline, or an attempt timed out on read after the request had
 * already been written.
 *
 * <p>Retryable: {@code true}, but a read timeout is ambiguous — the hub may have accepted the
 * message. The SDK retries it automatically only when idempotency support is declared.</p>
 */
public class NotifierTimeoutException extends NotifierException {

    public NotifierTimeoutException(String message) {
        super(message);
    }

    public NotifierTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
