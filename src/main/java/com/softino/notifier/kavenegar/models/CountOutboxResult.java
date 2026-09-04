package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/** Kavenegar-compatible outbox count result. Extends {@link CountInboxResult}. */
public class CountOutboxResult extends CountInboxResult {

    private final long sumPart;
    private final long cost;

    public CountOutboxResult(long startDate, long endDate, long sumCount, long sumPart, long cost) {
        super(startDate, endDate, sumCount);
        this.sumPart = sumPart;
        this.cost = cost;
    }

    @SuppressWarnings("unused")
    public CountOutboxResult(JsonObject json) {
        super(json);
        this.sumPart = json.has("sumpart") ? json.get("sumpart").getAsLong() : 0L;
        this.cost = json.has("cost") ? json.get("cost").getAsLong() : 0L;
    }

    public long getSumPart() {
        return sumPart;
    }

    public long getCost() {
        return cost;
    }
}
