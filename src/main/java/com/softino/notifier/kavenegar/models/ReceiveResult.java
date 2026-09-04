package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/** Kavenegar-compatible inbound receive result. Kept for source parity. */
public class ReceiveResult {

    private final long messageId;
    private final String message;
    private final String sender;
    private final String receptor;
    private final long date;

    public ReceiveResult(long messageId, String message, String sender, String receptor, long date) {
        this.messageId = messageId;
        this.message = message;
        this.sender = sender;
        this.receptor = receptor;
        this.date = date;
    }

    @SuppressWarnings("unused")
    public ReceiveResult(JsonObject json) {
        this(
                json.has("messageid") ? json.get("messageid").getAsLong() : 0L,
                json.has("message") ? json.get("message").getAsString() : null,
                json.has("sender") ? json.get("sender").getAsString() : null,
                json.has("receptor") ? json.get("receptor").getAsString() : null,
                json.has("date") ? json.get("date").getAsLong() : 0L);
    }

    public long getMessageId() {
        return messageId;
    }

    public String getMessage() {
        return message;
    }

    public String getSender() {
        return sender;
    }

    public String getReceptor() {
        return receptor;
    }

    public long getDate() {
        return date;
    }
}
