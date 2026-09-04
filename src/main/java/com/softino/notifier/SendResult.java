package com.softino.notifier;

import com.google.gson.JsonObject;

/**
 * Result of a single notification send, as returned by the Notifier core API.
 *
 * <p>Channel-agnostic: {@link #getId()} is the Notifier notification UUID, {@link #getStatus()}
 * is the coarse delivery status string ({@code queued}, {@code processing}, {@code delivered},
 * {@code failed}), and {@link #getChannelType()} identifies the channel that will carry it.</p>
 */
public final class SendResult {

    private final String id;                 // notification UUID
    private final String status;             // queued | processing | delivered | failed
    private final String channelType;
    private final String recipient;
    private final String subject;
    private final String body;
    private final String sendAt;             // RFC3339 or null
    private final String createdAt;
    private final String updatedAt;

    // Present when a status/log fetch includes per-provider detail.
    private final String provider;
    private final String providerMessageId;
    private final String error;

    public SendResult(String id, String status, String channelType, String recipient,
                      String subject, String body, String sendAt, String createdAt, String updatedAt) {
        this(id, status, channelType, recipient, subject, body, sendAt, createdAt, updatedAt, null, null, null);
    }

    public SendResult(String id, String status, String channelType, String recipient,
                      String subject, String body, String sendAt, String createdAt, String updatedAt,
                      String provider, String providerMessageId, String error) {
        this.id = id;
        this.status = status;
        this.channelType = channelType;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.sendAt = sendAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.provider = provider;
        this.providerMessageId = providerMessageId;
        this.error = error;
    }

    // ---- Core accessors ----

    public String getId() { return id; }
    public String getStatus() { return status; }
    public String getChannelType() { return channelType; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getSendAt() { return sendAt; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public String getProvider() { return provider; }
    /** The provider message id (e.g. Kavenegar {@code messageid}, SMS.ir {@code messageId}) when known. */
    public String getProviderMessageId() { return providerMessageId; }
    public String getError() { return error; }

    /**
     * Kavenegar-compat alias. In Notifier the identifier is a UUID, so this returns the
     * same value as {@link #getId()}. Use {@link #getId()} in new code; kept for drop-in
     * source compatibility with Kavenegar-java callers.
     */
    public String getMessageId() { return id; }

    // ---- Convenience ----

    public boolean isDelivered() { return "delivered".equalsIgnoreCase(status); }
    public boolean isFailed() { return "failed".equalsIgnoreCase(status); }

    public static SendResult from(JsonObject o) {
        String sendAt = o.has("send_at") && !o.get("send_at").isJsonNull() ? o.get("send_at").getAsString() : null;
        String subject = o.has("subject") && !o.get("subject").isJsonNull() ? o.get("subject").getAsString() : null;
        String error = o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
        String provider = o.has("provider") && !o.get("provider").isJsonNull() ? o.get("provider").getAsString() : null;
        String pmi = o.has("provider_message_id") && !o.get("provider_message_id").isJsonNull()
                ? o.get("provider_message_id").getAsString() : null;
        return new SendResult(
                o.has("id") ? o.get("id").getAsString() : null,
                o.has("status") ? o.get("status").getAsString() : null,
                o.has("channel_type") ? o.get("channel_type").getAsString() : null,
                o.has("recipient") ? o.get("recipient").getAsString() : null,
                subject,
                o.has("body") ? o.get("body").getAsString() : null,
                sendAt,
                o.has("created_at") ? o.get("created_at").getAsString() : null,
                o.has("updated_at") ? o.get("updated_at").getAsString() : null,
                provider, pmi, error);
    }

    @Override
    public String toString() {
        return "SendResult{id=" + id + ", status=" + status + ", channelType=" + channelType
                + ", recipient=" + recipient + "}";
    }
}
