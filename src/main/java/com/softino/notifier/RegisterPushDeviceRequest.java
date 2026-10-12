package com.softino.notifier;

/**
 * Input to {@link NotifierApi#registerPushDevice(RegisterPushDeviceRequest)}. Mirrors the
 * {@code POST /v1/push/devices} request body; use the builder for readability.
 */
public final class RegisterPushDeviceRequest {

    private final PushSubscription subscription;
    private final String userRef;
    private final String channelId;

    private RegisterPushDeviceRequest(Builder b) {
        this.subscription = b.subscription;
        this.userRef = b.userRef;
        this.channelId = b.channelId;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience for the common case: the subscription alone. */
    public static RegisterPushDeviceRequest of(PushSubscription subscription) {
        return new Builder().subscription(subscription).build();
    }

    /** The browser's subscription. Required; validated by {@link NotifierApi#registerPushDevice}. */
    public PushSubscription getSubscription() {
        return subscription;
    }

    /**
     * Your own user identifier for this device. Optional, but it is what makes listing all of one
     * user's browsers possible. Omitted from the request when null or blank.
     */
    public String getUserRef() {
        return userRef;
    }

    /**
     * Pins the vapid channel the browser subscribed through. Leave it null unless the tenant has
     * more than one VAPID key pair: the service then uses the highest-priority active vapid
     * channel and stores which one it used. Omitted from the request when null or blank.
     */
    public String getChannelId() {
        return channelId;
    }

    public static final class Builder {
        private PushSubscription subscription;
        private String userRef;
        private String channelId;

        public Builder subscription(PushSubscription subscription) {
            this.subscription = subscription;
            return this;
        }

        /**
         * Your own user identifier for this device. Omitted (or blank) on a re-registration it
         * keeps the stored binding, so a frontend that registers on every visit cannot silently
         * unlink a device. Pass a different value to rebind it, and use
         * {@link NotifierApi#deletePushDevice(String)} for logout.
         */
        public Builder userRef(String userRef) {
            this.userRef = userRef;
            return this;
        }

        public Builder channelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        public RegisterPushDeviceRequest build() {
            return new RegisterPushDeviceRequest(this);
        }
    }
}
