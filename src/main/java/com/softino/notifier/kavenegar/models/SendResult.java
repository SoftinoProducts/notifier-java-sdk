package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/**
 * Kavenegar-compatible send result. Field names and getters match Kavenegar-java so existing
 * callers compile unchanged after a package rename.
 */
public class SendResult {

    private final long messageId;
    private final String message;
    private final int status;
    private final String statusText;
    private final String sender;
    private final String receptor;
    private final long date;
    private final int cost;
    private final String notificationId;

    /** Primary constructor used by the facade when mapping from the Notifier platform. */
    public SendResult(long messageId, int status, String statusText, String message,
                      String sender, String receptor, long date, int cost) {
        this(messageId, status, statusText, message, sender, receptor, date, cost, null);
    }

    /** Constructor that also carries the Notifier notification UUID. */
    public SendResult(long messageId, int status, String statusText, String message,
                      String sender, String receptor, long date, int cost, String notificationId) {
        this.messageId = messageId;
        this.message = message;
        this.status = status;
        this.statusText = statusText;
        this.sender = sender;
        this.receptor = receptor;
        this.date = date;
        this.cost = cost;
        this.notificationId = notificationId;
    }

    /** Kavenegar-JSON constructor, kept for source parity. */
    @SuppressWarnings("unused")
    public SendResult(JsonObject json) {
        this(
                json.has("messageid") ? json.get("messageid").getAsLong() : 0L,
                json.has("status") ? json.get("status").getAsInt() : 0,
                json.has("statustext") ? json.get("statustext").getAsString() : null,
                json.has("message") ? json.get("message").getAsString() : null,
                json.has("sender") ? json.get("sender").getAsString() : null,
                json.has("receptor") ? json.get("receptor").getAsString() : null,
                json.has("date") ? json.get("date").getAsLong() : 0L,
                json.has("cost") ? json.get("cost").getAsInt() : 0,
                json.has("notification_id") ? json.get("notification_id").getAsString() : null);
    }

    public long getMessageId() {
        return messageId;
    }

    public String getMessage() {
        return message;
    }

    public int getStatus() {
        return status;
    }

    public String getStatusText() {
        return statusText;
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

    public int getCost() {
        return cost;
    }

    /** The Notifier notification UUID (present when mapped from a Notifier response). */
    public String getNotificationId() {
        return notificationId;
    }
}
