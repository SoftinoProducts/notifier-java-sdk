package com.softino.notifier;

import com.google.gson.JsonObject;

/**
 * The sender identity a frontend subscribes with, as returned by
 * {@code GET /v1/push/vapid-public-key}.
 *
 * <p>The service holds the VAPID key pair; the browser subscribes with the public half, and the
 * matching private half never leaves the service. Expose this through your own backend rather
 * than calling the Notifier API from the browser — the API key is a tenant credential.</p>
 */
public final class VapidKey {

    private final String channelId;
    private final String publicKey;
    private final String subject;

    public VapidKey(String channelId, String publicKey, String subject) {
        this.channelId = channelId;
        this.publicKey = publicKey;
        this.subject = subject;
    }

    public String getChannelId() {
        return channelId;
    }

    /** The {@code applicationServerKey} to pass to {@code pushManager.subscribe()}. */
    public String getPublicKey() {
        return publicKey;
    }

    /** The sender contact the push service sees (a {@code mailto:} or {@code https:} URL). */
    public String getSubject() {
        return subject;
    }

    public static VapidKey from(JsonObject o) {
        return new VapidKey(
                o.has("channel_id") && !o.get("channel_id").isJsonNull() ? o.get("channel_id").getAsString() : null,
                o.has("public_key") && !o.get("public_key").isJsonNull() ? o.get("public_key").getAsString() : null,
                o.has("subject") && !o.get("subject").isJsonNull() ? o.get("subject").getAsString() : null);
    }

    @Override
    public String toString() {
        return "VapidKey{channelId=" + channelId + ", subject=" + subject + "}";
    }
}
