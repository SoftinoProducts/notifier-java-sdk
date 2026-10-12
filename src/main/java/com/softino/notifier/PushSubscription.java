package com.softino.notifier;

import com.google.gson.JsonObject;

/**
 * A browser's Web Push subscription, exactly as {@code registration.pushManager.subscribe()}
 * produces it and {@code subscription.toJSON()} serialises it.
 *
 * <p>It is the one thing a Web Push send needs that the caller holds: the endpoint to POST to
 * and the two keys the payload is encrypted to (RFC 8291). Register it once with
 * {@link NotifierApi#registerPushDevice(PushSubscription)} — the service returns a device id —
 * and send to that id afterwards. The endpoint is a capability, so it is write-only through the
 * API and {@link PushDevice} never returns it.</p>
 *
 * <p>The VAPID keys are not part of this object. They belong to the channel and are configured
 * once on the service; the browser only needs {@link NotifierApi#getVapidPublicKey()} as its
 * {@code applicationServerKey}.</p>
 */
public final class PushSubscription {

    private final String endpoint;
    private final Long expirationTime;
    private final PushSubscriptionKeys keys;

    public PushSubscription(String endpoint, Long expirationTime, PushSubscriptionKeys keys) {
        this.endpoint = endpoint;
        this.expirationTime = expirationTime;
        this.keys = keys;
    }

    /** Convenience for the common case: an endpoint plus its two base64url keys. */
    public static PushSubscription of(String endpoint, String p256dh, String auth) {
        return new PushSubscription(endpoint, null, new PushSubscriptionKeys(p256dh, auth));
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sent by some browsers and ignored by the service; expiry is detected from the push
     * service's 404/410 answer instead. Omitted from the request when {@code null}.
     */
    public Long getExpirationTime() {
        return expirationTime;
    }

    public PushSubscriptionKeys getKeys() {
        return keys;
    }

    /** Builds the {@code subscription} object of {@code POST /v1/push/devices}. */
    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("endpoint", endpoint);
        if (expirationTime != null) o.addProperty("expirationTime", expirationTime);
        if (keys != null) o.add("keys", keys.toJson());
        return o;
    }

    @Override
    public String toString() {
        // The endpoint is a capability: keep it out of logs and error messages.
        return "PushSubscription{endpoint=<redacted>, expirationTime=" + expirationTime + "}";
    }

    /**
     * The client's public key material: the P-256 public key ({@code p256dh}) and the 16-byte
     * authentication secret ({@code auth}), both as base64url strings.
     */
    public static final class PushSubscriptionKeys {

        private final String p256dh;
        private final String auth;

        public PushSubscriptionKeys(String p256dh, String auth) {
            this.p256dh = p256dh;
            this.auth = auth;
        }

        public String getP256dh() {
            return p256dh;
        }

        public String getAuth() {
            return auth;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("p256dh", p256dh);
            o.addProperty("auth", auth);
            return o;
        }
    }
}
