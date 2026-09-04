package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;
import com.softino.notifier.kavenegar.enums.MessageStatus;

/** Kavenegar-compatible status-by-local-id result. Extends {@link StatusResult}. */
public class StatusLocalMessageIdResult extends StatusResult {

    private final long localId;

    public StatusLocalMessageIdResult(int messageId, MessageStatus status, String statusText, long localId) {
        super(messageId, status, statusText);
        this.localId = localId;
    }

    @SuppressWarnings("unused")
    public StatusLocalMessageIdResult(JsonObject json) {
        super(json);
        this.localId = json.has("localid") ? json.get("localid").getAsLong() : 0L;
    }

    public long getLocalId() {
        return localId;
    }
}
