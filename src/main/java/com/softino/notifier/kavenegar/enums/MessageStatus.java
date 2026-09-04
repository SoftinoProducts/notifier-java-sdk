package com.softino.notifier.kavenegar.enums;

/**
 * Kavenegar-compatible message status codes.
 *
 * <p>The constant names and numeric values are kept identical to Kavenegar-java so that
 * existing callers compile unchanged. Note {@link #Schulded} (a Kavenegar spelling) is
 * preserved verbatim for source compatibility.</p>
 */
public enum MessageStatus {
    Queued(1),
    Schulded(2),
    SentToCenter(4),
    Delivered(10),
    Undelivered(11),
    Canceled(13),
    Filtered(14),
    Received(50),
    Incorrect(100);

    private final int value;

    MessageStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static MessageStatus valueOf(int type) {
        for (MessageStatus code : values()) {
            if (type == code.value) {
                return code;
            }
        }
        return null;
    }
}
