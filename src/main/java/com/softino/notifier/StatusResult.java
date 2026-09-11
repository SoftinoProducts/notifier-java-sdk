package com.softino.notifier;

import com.google.gson.JsonObject;

/**
 * Delivery status of a notification, as returned by {@code GET /v1/notifications/{id}}.
 *
 * <p>{@link #getStatus()} is the coarse Notifier status string
 * ({@code queued}, {@code processing}, {@code delivered}, {@code failed}). When the status
 * record carries per-provider detail, {@link #getProvider()}, {@link #getProviderMessageId()}
 * and {@link #getError()} are populated.</p>
 */
public final class StatusResult {

    private final String id;
    private final String status;
    private final String channelType;
    private final String recipient;
    private final String subject;
    private final String body;
    private final String provider;
    private final String providerMessageId;
    private final String sentAt;
    private final String error;
    private final boolean simulated;

    public StatusResult(String id, String status, String channelType, String recipient,
                        String subject, String body, String provider, String providerMessageId,
                        String sentAt, String error) {
        this(id, status, channelType, recipient, subject, body, provider, providerMessageId, sentAt, error, false);
    }

    public StatusResult(String id, String status, String channelType, String recipient,
                        String subject, String body, String provider, String providerMessageId,
                        String sentAt, String error, boolean simulated) {
        this.id = id;
        this.status = status;
        this.channelType = channelType;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.provider = provider;
        this.providerMessageId = providerMessageId;
        this.sentAt = sentAt;
        this.error = error;
        this.simulated = simulated;
    }

    public String getId() { return id; }
    public String getStatus() { return status; }
    public String getChannelType() { return channelType; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getSentAt() { return sentAt; }
    public String getError() { return error; }

    /**
     * Whether this delivery was rehearsed rather than attempted: the provider was never
     * contacted.
     */
    public boolean isSimulated() { return simulated; }

    public boolean isDelivered() { return "delivered".equalsIgnoreCase(status); }
    public boolean isFailed() { return "failed".equalsIgnoreCase(status); }
    public boolean isQueued() { return "queued".equalsIgnoreCase(status); }

    public static StatusResult from(JsonObject o) {
        // Status detail reports the provider attempt time as `sent_at` (distinct from the
        // optional scheduled `send_at` on the notification itself).
        String sentAt = o.has("sent_at") && !o.get("sent_at").isJsonNull() ? o.get("sent_at").getAsString() : null;
        String subject = o.has("subject") && !o.get("subject").isJsonNull() ? o.get("subject").getAsString() : null;
        String error = o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString() : null;
        String provider = o.has("provider") && !o.get("provider").isJsonNull() ? o.get("provider").getAsString() : null;
        String pmi = o.has("provider_message_id") && !o.get("provider_message_id").isJsonNull()
                ? o.get("provider_message_id").getAsString() : null;
        return new StatusResult(
                o.has("id") ? o.get("id").getAsString() : null,
                o.has("status") ? o.get("status").getAsString() : null,
                o.has("channel_type") ? o.get("channel_type").getAsString() : null,
                o.has("recipient") ? o.get("recipient").getAsString() : null,
                subject,
                o.has("body") ? o.get("body").getAsString() : null,
                provider, pmi, sentAt, error,
                o.has("simulated") && !o.get("simulated").isJsonNull() && o.get("simulated").getAsBoolean());
    }

    @Override
    public String toString() {
        return "StatusResult{id=" + id + ", status=" + status + ", provider=" + provider + "}";
    }
}
