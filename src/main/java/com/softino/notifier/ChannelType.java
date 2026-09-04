package com.softino.notifier;

import java.util.Objects;

/**
 * A channel type in the Notifier platform.
 *
 * <p>This is an <b>open</b> value type (not a Java {@code enum}) so that new channels
 * are supported without shipping an SDK change. Known channels are exposed as static
 * constants for convenience; {@link #of(String)} accepts any string, so a future
 * channel such as {@code "telegram"} works immediately as long as the Notifier backend
 * recognises it.</p>
 *
 * <p>Compare with {@code ==} using {@link #equals(Object)} (value equality), never
 * reference identity.</p>
 */
public final class ChannelType {

    // Known Notifier channel types.
    public static final ChannelType SMS = new ChannelType("sms");
    public static final ChannelType EMAIL = new ChannelType("email");
    public static final ChannelType WEBPUSH = new ChannelType("webpush");

    // Common messaging channels — recognised by the backend when registered, and always
    // safely sendable through the generic core API regardless.
    public static final ChannelType TELEGRAM = new ChannelType("telegram");
    public static final ChannelType SLACK = new ChannelType("slack");
    public static final ChannelType BALE = new ChannelType("bale");

    private final String value;

    private ChannelType(String value) {
        Objects.requireNonNull(value, "value");
        this.value = value;
    }

    /** Returns the canonical string value used as {@code channel_type} in API requests. */
    public String getValue() {
        return value;
    }

    /**
     * Returns a ChannelType for <b>any</b> string. This is the extension point: pass
     * {@code "telegram"}, {@code "slack"}, {@code "bale"}, or a backend-specific value
     * and the SDK will send it through the generic channel-agnostic path.
     */
    public static ChannelType of(String value) {
        Objects.requireNonNull(value, "value");
        for (ChannelType known : values()) {
            if (known.value.equals(value)) {
                return known;
            }
        }
        return new ChannelType(value);
    }

    /** Returns all built-in constants. */
    public static ChannelType[] values() {
        return new ChannelType[]{SMS, EMAIL, WEBPUSH, TELEGRAM, SLACK, BALE};
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChannelType)) return false;
        ChannelType that = (ChannelType) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
