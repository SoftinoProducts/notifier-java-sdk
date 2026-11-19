package com.softino.notifier.exception;

/**
 * Thrown when a transport-level failure occurs (non-2xx HTTP, network error).
 * Aligned with Kavenegar's {@code HttpException} for drop-in compatibility.
 */
public class HttpException extends NotifierException {

    private final int code;

    public HttpException(String message, int code) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * Transport-level 408/429/5xx responses are transient; other statuses and plain network
     * failures are not necessarily safe to retry. Kept for the blocking API, whose behaviour
     * is otherwise unchanged.
     */
    @Override
    public boolean isRetryable() {
        return code == 408 || code == 429 || code >= 500;
    }
}
