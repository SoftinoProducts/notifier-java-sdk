package com.softino.notifier.exception;

/**
 * Thrown when the Notifier API returns a non-success business status. The code is the
 * machine error code when one is present, falling back to the HTTP status. Callers that
 * need the Kavenegar-compatible {@code MetaData} view should use the
 * {@code com.softino.notifier.kavenegar} facade instead.
 */
public class ApiException extends NotifierException {

    private final int code;

    public ApiException(String message, int code) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
