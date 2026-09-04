package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;
import com.softino.notifier.kavenegar.enums.MessageStatus;

/**
 * Kavenegar-compatible status result. {@link #getStatus()} returns a {@link MessageStatus},
 * exactly like Kavenegar-java.
 */
public class StatusResult {

    private final int messageId;
    private final MessageStatus status;
    private final String statusText;
    private final String notificationId;

    /** Primary constructor used by the facade when mapping from the Notifier platform. */
    public StatusResult(int messageId, MessageStatus status, String statusText) {
        this(messageId, status, statusText, null);
    }

    /** Constructor that also carries the Notifier notification UUID. */
    public StatusResult(int messageId, MessageStatus status, String statusText, String notificationId) {
        this.messageId = messageId;
        this.status = status;
        this.statusText = statusText;
        this.notificationId = notificationId;
    }

    /** Kavenegar-JSON constructor, kept for source parity. */
    @SuppressWarnings("unused")
    public StatusResult(JsonObject json) {
        this(
                json.has("messageid") ? json.get("messageid").getAsInt() : 0,
                json.has("status") ? MessageStatus.valueOf(json.get("status").getAsInt()) : null,
                json.has("statustext") ? json.get("statustext").getAsString() : null,
                json.has("notification_id") ? json.get("notification_id").getAsString() : null);
    }

    public int getMessageId() {
        return messageId;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public String getStatusText() {
        return statusText;
    }

    /** The Notifier notification UUID (present when mapped from a Notifier response). */
    public String getNotificationId() {
        return notificationId;
    }
}
