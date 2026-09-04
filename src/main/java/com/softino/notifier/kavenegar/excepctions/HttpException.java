package com.softino.notifier.kavenegar.excepctions;

/**
 * Kavenegar-compatible HTTP exception. {@link #getCode()} returns the HTTP status code.
 * Trade-hub's retry logic keys on this type and code (411/408/429/500/502/503/504).
 */
public class HttpException extends BaseException {

    private final int code;

    public HttpException(String message, int code) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
