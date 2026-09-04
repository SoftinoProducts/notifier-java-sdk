package com.softino.notifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional per-send configuration, mirroring the Notifier
 * {@code POST /v1/notifications} request fields that are not part of the core
 * recipient + content pair.
 *
 * <p>All fields are optional. Use the builder ({@link #builder()}) for readability.</p>
 */
public final class SendOptions {

    private final String channelId;       // optional channel override (UUID)
    private final String groupId;         // optional load-balanced channel group (UUID)
    private final String templateId;      // optional template (UUID); content is rendered server-side
    private final String templateName;    // optional template name; resolved server-side
    private final Map<String, Object> templateVars;
    private final String idempotencyKey;
    private final String sendAt;          // ISO-8601 / RFC3339 timestamp for scheduled send
    private final String callbackUrl;
    private final Map<String, Object> metadata;
    private final String locale;

    private SendOptions(Builder b) {
        this.channelId = b.channelId;
        this.groupId = b.groupId;
        this.templateId = b.templateId;
        this.templateName = b.templateName;
        this.templateVars = b.templateVars == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.templateVars));
        this.idempotencyKey = b.idempotencyKey;
        this.sendAt = b.sendAt;
        this.callbackUrl = b.callbackUrl;
        this.metadata = b.metadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.metadata));
        this.locale = b.locale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SendOptions none() {
        return new Builder().build();
    }

    public String getChannelId() { return channelId; }
    public String getGroupId() { return groupId; }
    public String getTemplateId() { return templateId; }
    public String getTemplateName() { return templateName; }
    public Map<String, Object> getTemplateVars() { return templateVars; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSendAt() { return sendAt; }
    public String getCallbackUrl() { return callbackUrl; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getLocale() { return locale; }

    public static final class Builder {
        private String channelId;
        private String groupId;
        private String templateId;
        private String templateName;
        private Map<String, Object> templateVars;
        private String idempotencyKey;
        private String sendAt;
        private String callbackUrl;
        private Map<String, Object> metadata;
        private String locale;

        public Builder channelId(String channelId) { this.channelId = channelId; return this; }
        public Builder groupId(String groupId) { this.groupId = groupId; return this; }
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
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        /** ISO-8601/RFC3339 timestamp for a scheduled send, e.g. {@code 2026-01-01T00:00:00Z}. */
        public Builder sendAt(String sendAt) { this.sendAt = sendAt; return this; }
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
            return this;
        }
        public Builder locale(String locale) { this.locale = locale; return this; }

        public SendOptions build() {
            return new SendOptions(this);
        }
    }
}
