package com.softino.notifier;

import com.google.gson.JsonObject;

/**
 * A registered browser Web Push subscription, as the API reports it.
 *
 * <p>The subscription itself is deliberately absent: its endpoint is a capability, so it is
 * write-only through this API. A caller that wants to send holds {@link #getId()} and passes it
 * to {@link NotifierApi#sendToDevice(String, String)} or {@link NotifierApi#deviceRecipient(String)}.</p>
 */
public final class PushDevice {

    private final String id;
    private final String userRef;
    private final String channelId;
    private final String endpointHost;
    private final boolean isActive;
    private final int failureCount;
    private final String lastSuccessAt;
    private final String lastFailureAt;
    private final String createdAt;
    private final String updatedAt;

    public PushDevice(String id, String userRef, String channelId, String endpointHost, boolean isActive,
                      int failureCount, String lastSuccessAt, String lastFailureAt,
                      String createdAt, String updatedAt) {
        this.id = id;
        this.userRef = userRef;
        this.channelId = channelId;
        this.endpointHost = endpointHost;
        this.isActive = isActive;
        this.failureCount = failureCount;
        this.lastSuccessAt = lastSuccessAt;
        this.lastFailureAt = lastFailureAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    /** The tenant's own user identifier for this device; opaque to the service. */
    public String getUserRef() {
        return userRef;
    }

    /**
     * The vapid channel the subscription is bound to, or {@code null} when the response omitted
     * it. A subscription only works with that channel, because it was created with that channel's
     * VAPID public key.
     */
    public String getChannelId() {
        return channelId;
    }

    /** The push service host, for display (e.g. {@code fcm.googleapis.com}). */
    public String getEndpointHost() {
        return endpointHost;
    }

    public boolean isActive() {
        return isActive;
    }

    /** Consecutive failed pushes since the last success. */
    public int getFailureCount() {
        return failureCount;
    }

    public String getLastSuccessAt() {
        return lastSuccessAt;
    }

    public String getLastFailureAt() {
        return lastFailureAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Whether the device can still be sent to. The service deactivates a device when the push
     * service reports its subscription gone (HTTP 404/410), so a deactivated device is a
     * logout or an expired browser, not a transport failure.
     */
    public boolean isUsable() {
        return isActive;
    }

    public static PushDevice from(JsonObject o) {
        return new PushDevice(
                string(o, "id"),
                string(o, "user_ref"),
                string(o, "channel_id"),
                string(o, "endpoint_host"),
                o.has("is_active") && !o.get("is_active").isJsonNull() && o.get("is_active").getAsBoolean(),
                o.has("failure_count") && !o.get("failure_count").isJsonNull()
                        ? o.get("failure_count").getAsInt() : 0,
                string(o, "last_success_at"),
                string(o, "last_failure_at"),
                string(o, "created_at"),
                string(o, "updated_at"));
    }

    private static String string(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    @Override
    public String toString() {
        return "PushDevice{id=" + id + ", userRef=" + userRef + ", active=" + isActive + "}";
    }
}
