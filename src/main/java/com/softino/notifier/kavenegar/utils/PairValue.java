package com.softino.notifier.kavenegar.utils;

/** Kavenegar-compatible key/value pair, used for extra verify-lookup token slots. */
public class PairValue {

    private final String key;
    private final String value;

    public PairValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
