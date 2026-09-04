package com.softino.notifier.kavenegar.utils;

import com.softino.notifier.kavenegar.enums.MessageStatus;

/**
 * Maps between Notifier's coarse status model and Kavenegar's {@link MessageStatus} enum +
 * statusText. Notifier reports {@code queued/processing/delivered/failed}; Kavenegar exposes a
 * richer set of numeric statuses and Persian status texts. These maps port one to the other so
 * the Kavenegar facade reports sensible values.
 *
 * <p>Notifier-specific semantics (e.g. a literal {@code status == 5}) do not exist in Notifier;
 * the mapping is best-effort and deliberately conservative.</p>
 */
public final class StatusMapping {

    private StatusMapping() {
    }

    /** Maps a Notifier coarse status string to the closest Kavenegar MessageStatus. */
    public static MessageStatus toKavenegarStatus(String notifierStatus) {
        if (notifierStatus == null) return null;
        switch (notifierStatus.toLowerCase()) {
            case "queued":     return MessageStatus.Queued;
            case "processing": return MessageStatus.SentToCenter;
            case "delivered":  return MessageStatus.Delivered;
            case "failed":     return MessageStatus.Undelivered;
            default:           return null;
        }
    }

    /** Maps a Notifier coarse status string to a Kavenegar-style numeric status. */
    public static int toStatusInt(String notifierStatus) {
        MessageStatus ms = toKavenegarStatus(notifierStatus);
        return ms == null ? MessageStatus.Incorrect.getValue() : ms.getValue();
    }

    /**
     * Maps a Notifier coarse status string to a Kavenegar-style statusText. This lets callers
     * that key on a specific statusText (e.g. {@code "ارسال به مخابرات"}) behave meaningfully.
     */
    public static String toStatusText(String notifierStatus) {
        if (notifierStatus == null) return "";
        switch (notifierStatus.toLowerCase()) {
            case "queued":     return "در صف ارسال";
            case "processing": return "ارسال به مخابرات";
            case "delivered":  return "تحویل شد";
            case "failed":     return "ناموفق";
            default:           return notifierStatus;
        }
    }

    /** Maps a Kavenegar MessageStatus back to a Notifier coarse status string. */
    public static String toNotifierStatus(MessageStatus ks) {
        if (ks == null) return "failed";
        switch (ks) {
            case Queued:       return "queued";
            case Schulded:     return "queued";
            case SentToCenter: return "processing";
            case Delivered:    return "delivered";
            case Received:     return "delivered";
            case Undelivered:  return "failed";
            case Canceled:     return "failed";
            case Filtered:     return "failed";
            case Incorrect:    return "failed";
            default:           return "failed";
        }
    }
}
