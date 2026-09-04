package com.softino.notifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single entry within a bulk send. Mirrors the {@code messages[]} item of
 * {@code POST /v1/notifications/bulk}.
 */
public final class RecipientMessage {

    private final String recipient;
    private final Content content;
    private final String templateId;
    private final String templateName;
    private final Map<String, Object> templateVars;
    private final String callbackUrl;
    private final Map<String, Object> metadata;

    private RecipientMessage(Builder b) {
        this.recipient = Objects.requireNonNull(b.recipient, "recipient");
        this.content = b.content;
        this.templateId = b.templateId;
        this.templateName = b.templateName;
        this.templateVars = b.templateVars == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.templateVars));
        this.callbackUrl = b.callbackUrl;
        this.metadata = b.metadata == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.metadata));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RecipientMessage of(String recipient, Content content) {
        return new Builder().recipient(recipient).content(content).build();
    }

    public String getRecipient() { return recipient; }
    public Content getContent() { return content; }
    public String getTemplateId() { return templateId; }
    public String getTemplateName() { return templateName; }
    public Map<String, Object> getTemplateVars() { return templateVars; }
    public String getCallbackUrl() { return callbackUrl; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static final class Builder {
        private String recipient;
        private Content content;
        private String templateId;
        private String templateName;
        private Map<String, Object> templateVars;
        private String callbackUrl;
        private Map<String, Object> metadata;

        public Builder recipient(String recipient) { this.recipient = recipient; return this; }
        public Builder content(Content content) { this.content = content; return this; }
        /** Sets body directly (convenience). */
        public Builder body(String body) { this.content = new Content(null, body); return this; }
        public Builder subjectAndBody(String subject, String body) {
            this.content = new Content(subject, body);
            return this;
        }
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
        public Builder callbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; return this; }
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
            return this;
        }

        public RecipientMessage build() {
            return new RecipientMessage(this);
        }
    }
}
