package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/** Kavenegar-compatible inbox count result. */
public class CountInboxResult {

    private final long startDate;
    private final long endDate;
    private final long sumCount;

    public CountInboxResult(long startDate, long endDate, long sumCount) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.sumCount = sumCount;
    }

    @SuppressWarnings("unused")
    public CountInboxResult(JsonObject json) {
        this(
                json.has("startdate") ? json.get("startdate").getAsLong() : 0L,
                json.has("enddate") ? json.get("enddate").getAsLong() : 0L,
                json.has("sumcount") ? json.get("sumcount").getAsLong() : 0L);
    }

    public long getStartDate() {
        return startDate;
    }

    public long getEndDate() {
        return endDate;
    }

    public long getSumCount() {
        return sumCount;
    }
}
