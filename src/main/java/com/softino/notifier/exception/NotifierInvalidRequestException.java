package com.softino.notifier.exception;

/**
 * The hub rejected the request as invalid (4xx other than 429), or the request could not be
 * built locally.
 *
 * <p>Retryable: {@code false}. Retrying the same payload will fail the same way.</p>
 */
public class NotifierInvalidRequestException extends NotifierException {

    private final int httpStatus;

    public NotifierInvalidRequestException(String message) {
        this(message, 0);
    }

    public NotifierInvalidRequestException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public NotifierInvalidRequestException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** The HTTP status the hub returned, or 0 when the request was rejected locally. */
    public int getHttpStatus() {
        return httpStatus;
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
