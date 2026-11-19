package com.softino.notifier.exception;

/**
 * The client has been closed (or is closing) and no longer accepts sends.
 *
 * <p>Retryable: {@code false}. Create a new client if sends must resume.</p>
 */
public class NotifierClosedException extends NotifierException {

    public NotifierClosedException(String message) {
        super(message);
    }

    public NotifierClosedException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
