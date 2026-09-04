package com.softino.notifier;

import java.util.Objects;

/**
 * The message body of a notification. Mirrors the Notifier API {@code content} object.
 *
 * <p>{@code subject} is optional and only meaningful for channels that support it
 * (e.g. email). {@code body} is the payload text for most channels.</p>
 */
public final class Content {

    private final String subject;
    private final String body;

    public Content(String body) {
        this(null, body);
    }

    public Content(String subject, String body) {
        Objects.requireNonNull(body, "body");
        this.subject = subject;
        this.body = body;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public static Content of(String body) {
        return new Content(body);
    }

    @Override
    public String toString() {
        return "Content{subject=" + subject + ", body=" + body + "}";
    }
}
