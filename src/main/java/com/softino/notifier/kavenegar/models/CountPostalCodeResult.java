package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/** Kavenegar-compatible postal-code count result. */
public class CountPostalCodeResult {

    private final String section;
    private final long value;

    public CountPostalCodeResult(String section, long value) {
        this.section = section;
        this.value = value;
    }

    @SuppressWarnings("unused")
    public CountPostalCodeResult(JsonObject json) {
        this(
                json.has("section") ? json.get("section").getAsString() : null,
                json.has("value") ? json.get("value").getAsLong() : 0L);
    }

    public String getSection() {
        return section;
    }

    public long getValue() {
        return value;
    }
}
