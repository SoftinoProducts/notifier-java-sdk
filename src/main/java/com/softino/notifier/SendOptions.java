package com.softino.notifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional per-send configuration, mirroring the Notifier
 * {@code POST /v1/notifications} request fields that are not part of the core
 * recipient + content pair.
 *
 * <p>All fields are optional. Use the builder ({@link #builder()}) for readability.</p>
 *
 * <p>The asynchronous-only fields ({@code deadline}, {@code traceId}, {@code priority},
 * {@code maxRetries}, {@code fireAndForget}, {@code connectTimeout}, {@code readTimeout}) are
 * ignored by the legacy blocking methods, whose behaviour is unchanged.</p>
 */
public final class SendOptions {

    private final String channelId;       // optional channel override (UUID)
    private final String groupId;         // optional load-balanced channel group (UUID)
    private final String groupName;       // optional channel group name (resolved server-side)
    private final String routeName;       // optional saved fan-out route (resolved server-side)
    private final String templateId;      // optional template (UUID); content is rendered server-side
    private final String templateName;    // optional template name; resolved server-side
    private final Map<String, Object> templateVars;
    private final List<Object> templateParams; // ordered values; bound positionally to the template's param_names
    private final String idempotencyKey;
    private final String sendAt;          // ISO-8601 / RFC3339 timestamp for scheduled send
    private final String callbackUrl;
    private final Map<String, Object> metadata;
    private final String locale;
    private final boolean simulated;

    // Asynchronous-only fields. The blocking path ignores them.
    private final Duration deadline;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String traceId;
    private final SendPriority priority;
    private final Integer maxRetries;
    private final boolean fireAndForget;

    private SendOptions(Builder b) {
        this.channelId = b.channelId;
        this.groupId = b.groupId;
        this.groupName = b.groupName;
        this.routeName = b.routeName;
        this.templateId = b.templateId;
        this.templateName = b.templateName;
        this.templateVars = b.templateVars == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.templateVars));
        this.templateParams = b.templateParams == null ? null
                : Collections.unmodifiableList(new ArrayList<Object>(b.templateParams));
        this.idempotencyKey = b.idempotencyKey;
        this.sendAt = b.sendAt;
        this.callbackUrl = b.callbackUrl;
        this.metadata = b.metadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.metadata));
        this.locale = b.locale;
        this.simulated = b.simulated;

        this.deadline = b.deadline;
        this.connectTimeout = b.connectTimeout;
        this.readTimeout = b.readTimeout;
        this.traceId = b.traceId;
        this.priority = b.priority;
        this.maxRetries = b.maxRetries;
        this.fireAndForget = b.fireAndForget;
    }

    /**
     * Copy with a resolved idempotency key. Used by the async path: it generates a key when
     * the consumer did not supply one, then builds the request body with that key.
     */
    private SendOptions(SendOptions o, String resolvedIdempotencyKey) {
        this.channelId = o.channelId;
        this.groupId = o.groupId;
        this.groupName = o.groupName;
        this.routeName = o.routeName;
        this.templateId = o.templateId;
        this.templateName = o.templateName;
        this.templateVars = o.templateVars;
        this.templateParams = o.templateParams;
        this.idempotencyKey = resolvedIdempotencyKey;
        this.sendAt = o.sendAt;
        this.callbackUrl = o.callbackUrl;
        this.metadata = o.metadata;
        this.locale = o.locale;
        this.simulated = o.simulated;
        this.deadline = o.deadline;
        this.connectTimeout = o.connectTimeout;
        this.readTimeout = o.readTimeout;
        this.traceId = o.traceId;
        this.priority = o.priority;
        this.maxRetries = o.maxRetries;
        this.fireAndForget = o.fireAndForget;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SendOptions none() {
        return new Builder().build();
    }

    public String getChannelId() { return channelId; }
    public String getGroupId() { return groupId; }
    public String getGroupName() { return groupName; }
    public String getRouteName() { return routeName; }
    public String getTemplateId() { return templateId; }
    public String getTemplateName() { return templateName; }
    public Map<String, Object> getTemplateVars() { return templateVars; }
    public List<Object> getTemplateParams() { return templateParams; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSendAt() { return sendAt; }
    public String getCallbackUrl() { return callbackUrl; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getLocale() { return locale; }

    /** Total send deadline for async sends, including queueing, connect, write, read and retries. */
    public Duration getDeadline() { return deadline; }

    /** Per-attempt TCP connect timeout override for async sends. */
    public Duration getConnectTimeout() { return connectTimeout; }

    /** Per-attempt read timeout override for async sends. */
    public Duration getReadTimeout() { return readTimeout; }

    /** Correlation id copied to the {@code X-Trace-Id} header and the SDK's log context. */
    public String getTraceId() { return traceId; }

    /** Lane override; when unset the call site picks {@code HIGH} or {@code BULK}. */
    public SendPriority getPriority() { return priority; }

    /** Retry cap override for this send; when unset the client default applies. */
    public Integer getMaxRetries() { return maxRetries; }

    /**
     * Hint that the caller will not observe the result. It is implied by the
     * {@code *AndForget} methods; on an {@code *Async} call it still returns a future, but a
     * terminal failure is also counted in {@code notifier.send.dropped}.
     */
    public boolean isFireAndForget() { return fireAndForget; }

    /**
     * Whether this send is rehearsed instead of delivered: the service runs its whole pipeline
     * but never contacts the provider, reporting the notification as delivered by "simulator".
     */
    public boolean isSimulated() { return simulated; }

    /** Internal: copy with the resolved idempotency key (or the caller's own key). */
    SendOptions withIdempotencyKey(String key) {
        return new SendOptions(this, key);
    }

    /**
     * A builder pre-filled with every value of this instance. Useful to derive one send from
     * another (for example to add a {@code traceId} or raise the priority) without rebuilding
     * all fields.
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.channelId = this.channelId;
        b.groupId = this.groupId;
        b.groupName = this.groupName;
        b.routeName = this.routeName;
        b.templateId = this.templateId;
        b.templateName = this.templateName;
        b.templateVars = this.templateVars == null ? null : new LinkedHashMap<String, Object>(this.templateVars);
        b.templateParams = this.templateParams == null ? null : new ArrayList<Object>(this.templateParams);
        b.idempotencyKey = this.idempotencyKey;
        b.sendAt = this.sendAt;
        b.callbackUrl = this.callbackUrl;
        b.metadata = this.metadata == null ? null : new LinkedHashMap<String, Object>(this.metadata);
        b.locale = this.locale;
        b.simulated = this.simulated;
        b.deadline = this.deadline;
        b.connectTimeout = this.connectTimeout;
        b.readTimeout = this.readTimeout;
        b.traceId = this.traceId;
        b.priority = this.priority;
        b.maxRetries = this.maxRetries;
        b.fireAndForget = this.fireAndForget;
        return b;
    }

    public static final class Builder {
        private String channelId;
        private String groupId;
        private String groupName;
        private String routeName;
        private String templateId;
        private String templateName;
        private Map<String, Object> templateVars;
        private List<Object> templateParams;
        private String idempotencyKey;
        private String sendAt;
        private String callbackUrl;
        private Map<String, Object> metadata;
        private String locale;
        private boolean simulated;

        private Duration deadline;
        private Duration connectTimeout;
        private Duration readTimeout;
        private String traceId;
        private SendPriority priority;
        private Integer maxRetries;
        private boolean fireAndForget;

        public Builder channelId(String channelId) { this.channelId = channelId; return this; }
        public Builder groupId(String groupId) { this.groupId = groupId; return this; }
        /** Reference a channel group by name; the backend resolves it to a group id for routing. */
        public Builder groupName(String groupName) { this.groupName = groupName; return this; }
        /**
         * Reference a saved fan-out route by name. The route defines every channel,
         * template and recipient source, so {@code channel_type} is omitted from the
         * request and the channel type argument is ignored.
         */
        public Builder routeName(String routeName) { this.routeName = routeName; return this; }
        public Builder templateId(String templateId) { this.templateId = templateId; return this; }
        public Builder templateName(String templateName) { this.templateName = templateName; return this; }
        public Builder templateVar(String name, Object value) {
            if (this.templateVars == null) this.templateVars = new LinkedHashMap<>();
            this.templateVars.put(name, value);
            return this;
        }
        public Builder templateVars(Map<String, Object> templateVars) {
            this.templateVars = templateVars == null ? null : new LinkedHashMap<>(templateVars);
            return this;
        }
        /** Appends an ordered value; bound positionally to the template's declared param_names. */
        public Builder templateParam(Object value) {
            if (this.templateParams == null) this.templateParams = new ArrayList<>();
            this.templateParams.add(value);
            return this;
        }
        public Builder templateParams(List<Object> templateParams) {
            this.templateParams = templateParams == null ? null : new ArrayList<>(templateParams);
            return this;
        }

        /**
         * Idempotency key for this send. The async path sends it as {@code Idempotency-Key}
         * (and in the request body) on every attempt. When unset, the SDK generates one and
         * returns it from {@link SendResult#getIdempotencyKey()} on the async path, so a
         * result can still be correlated with the hub's record.
         */
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }

        /** ISO-8601/RFC3339 timestamp for a scheduled send, e.g. {@code 2026-01-01T00:00:00Z}. */
        public Builder sendAt(String sendAt) { this.sendAt = sendAt; return this; }
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
            return this;
        }
        public Builder locale(String locale) { this.locale = locale; return this; }

        /**
         * Rehearse the send: routing, group selection, template rendering and the panel all
         * behave normally, but the provider is never contacted and nothing is delivered. Your
         * templates, groups and channels stay exactly as they are.
         *
         * <p>{@link SendResult#isSimulated()} reports whether the delivery was rehearsed, as the
         * service recorded it.</p>
         */
        public Builder simulated(boolean simulated) { this.simulated = simulated; return this; }

        /**
         * Total send deadline, including queueing, connect, write, read and any retries. When
         * the deadline elapses the send fails with {@code NotifierTimeoutException}; the SDK
         * never waits past it. Defaults to 10 seconds.
         */
        public Builder deadline(Duration deadline) { this.deadline = requireNonNegative("deadline", deadline); return this; }

        /** Per-attempt connect timeout override (default 2 seconds). */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = requirePositive("connectTimeout", timeout);
            return this;
        }

        /** Per-attempt read timeout override (default 5 seconds). */
        public Builder readTimeout(Duration timeout) {
            this.readTimeout = requirePositive("readTimeout", timeout);
            return this;
        }

        /** Correlation id copied to the {@code X-Trace-Id} header and the SDK log context. */
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }

        /** Lane override; OTP traffic belongs on {@link SendPriority#HIGH}. */
        public Builder priority(SendPriority priority) { this.priority = priority; return this; }

        /** Retry cap override for this send (0 disables retries). */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** Hint that the caller will not observe the future; failures also count as dropped. */
        public Builder fireAndForget(boolean fireAndForget) {
            this.fireAndForget = fireAndForget;
            return this;
        }

        private static Duration requireNonNegative(String name, Duration value) {
            if (value != null && value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }

        private static Duration requirePositive(String name, Duration value) {
            if (value != null && (value.isNegative() || value.isZero())) {
                throw new IllegalArgumentException(name + " must be > 0");
            }
            return value;
        }

        public SendOptions build() {
            return new SendOptions(this);
        }
    }
}
