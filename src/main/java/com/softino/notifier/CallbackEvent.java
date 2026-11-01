package com.softino.notifier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Objects;

/**
 * The body of a delivery callback.
 *
 * <p>It is a status event, not a notification: the identifier field is
 * {@code notification_id} (not {@code id}), and a route child carries {@code fanout_id} and
 * {@code channel_type} so the receiver can correlate it with the route send. A
 * single-channel send omits {@code fanout_id}. A skipped optional target is never
 * dispatched, so it produces no callback at all.</p>
 *
 * <p>Parse it only after {@link CallbackVerifier} has accepted the request; the signature
 * covers the raw bytes, not this object.</p>
 */
public final class CallbackEvent {

    private final String notificationId;
    private final String tenantId;
    private final String status;
    private final ChannelType channelType;
    private final String fanoutId;
    private final String provider;
    private final String providerMessageId;
    private final boolean simulated;
    private final String error;
    private final String timestamp;

    public CallbackEvent(String notificationId, String tenantId, String status, ChannelType channelType,
                         String fanoutId, String provider, String providerMessageId, boolean simulated,
                         String error, String timestamp) {
        this.notificationId = notificationId;
        this.tenantId = tenantId;
        this.status = status;
        this.channelType = channelType;
        this.fanoutId = fanoutId;
        this.provider = provider;
        this.providerMessageId = providerMessageId;
        this.simulated = simulated;
        this.error = error;
        this.timestamp = timestamp;
    }

    /** Parses the callback body returned by the service. */
    public static CallbackEvent fromJson(String json) {
        Objects.requireNonNull(json, "json");
        return from(JsonParser.parseString(json).getAsJsonObject());
    }

    public static CallbackEvent from(JsonObject o) {
        String channel = string(o, "channel_type");
        return new CallbackEvent(
                string(o, "notification_id"),
                string(o, "tenant_id"),
                string(o, "status"),
                channel == null ? null : ChannelType.of(channel),
                string(o, "fanout_id"),
                string(o, "provider"),
                string(o, "provider_message_id"),
                o.has("simulated") && !o.get("simulated").isJsonNull() && o.get("simulated").getAsBoolean(),
                string(o, "error"),
                string(o, "timestamp"));
    }

    /** The notification (or, for a route send, the leg) whose status changed. */
    public String getNotificationId() {
        return notificationId;
    }

    /** The tenant that owns the notification. */
    public String getTenantId() {
        return tenantId;
    }

    /** {@code delivered} or {@code failed}; a callback is always a terminal outcome. */
    public String getStatus() {
        return status;
    }

    /** The channel this leg travelled over, or {@code null} when the body omitted it. */
    public ChannelType getChannelType() {
        return channelType;
    }

    /** Groups the legs of one route send; {@code null} for a single-channel send. */
    public String getFanoutId() {
        return fanoutId;
    }

    /** The provider that handled the delivery, for example {@code telegram}. */
    public String getProvider() {
        return provider;
    }

    /** The provider's own identifier for the message, when it returned one. */
    public String getProviderMessageId() {
        return providerMessageId;
    }

    /** True for a rehearsal send: the message was not actually delivered. */
    public boolean isSimulated() {
        return simulated;
    }

    /** The provider's failure reason, or {@code null} when the delivery succeeded. */
    public String getError() {
        return error;
    }

    /** When the delivery attempt finished, as the RFC 3339 string the service sent. */
    public String getTimestamp() {
        return timestamp;
    }

    public boolean isDelivered() {
        return "delivered".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }

    private static String string(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    @Override
    public String toString() {
        return "CallbackEvent{notificationId=" + notificationId + ", status=" + status
                + ", channelType=" + (channelType == null ? null : channelType.getValue())
                + ", fanoutId=" + fanoutId + "}";
    }
}
