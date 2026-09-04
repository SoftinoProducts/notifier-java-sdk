package com.softino.notifier.kavenegar.excepctions;

import com.softino.notifier.kavenegar.enums.MetaData;

/**
 * Kavenegar-compatible API exception. {@link #getCode()} returns a {@link MetaData} value,
 * exactly like Kavenegar-java. When a Notifier error cannot be mapped to a {@link MetaData}
 * value, {@link #getCode()} returns null and the message carries the detail.
 */
public class ApiException extends BaseException {

    private final int code;

    public ApiException(String message, int code) {
        super(message);
        this.code = code;
    }

    /** Aligns with Kavenegar-java: returns a {@link MetaData} (may be null if unmapped). */
    public MetaData getCode() {
        return MetaData.valueOf(code);
    }

    /** Raw numeric code (Notifier HTTP/business status) when the MetaData mapping is null. */
    public int getCodeInt() {
        return code;
    }
}
