package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/** Kavenegar-compatible account info result. */
public class AccountInfoResult {

    private final long remainCredit;
    private final long expireDate;
    private final String type;

    public AccountInfoResult(long remainCredit, long expireDate, String type) {
        this.remainCredit = remainCredit;
        this.expireDate = expireDate;
        this.type = type;
    }

    @SuppressWarnings("unused")
    public AccountInfoResult(JsonObject json) {
        this(
                json.has("remaincredit") ? json.get("remaincredit").getAsLong() : 0L,
                json.has("expiredate") ? json.get("expiredate").getAsLong() : 0L,
                json.has("type") ? json.get("type").getAsString() : null);
    }

    public long getRemainCredit() {
        return remainCredit;
    }

    public long getExpireDate() {
        return expireDate;
    }

    public String getType() {
        return type;
    }
}
