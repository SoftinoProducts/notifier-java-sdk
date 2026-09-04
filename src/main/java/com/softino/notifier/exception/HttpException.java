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
}
