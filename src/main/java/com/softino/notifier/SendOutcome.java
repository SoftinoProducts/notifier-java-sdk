package com.softino.notifier;

/**
 * Terminal outcome of an asynchronous send, as reported to a {@link SendListener}.
 *
 * <p>The same value is used as the {@code outcome} tag on the
 * {@code notifier.send.duration} timer.</p>
 */
public enum SendOutcome {

    /** The hub accepted the notification (2xx). Delivery to the handset is still pending. */
    SUCCESS,

    /** The send failed with a typed SDK exception. */
    FAILURE,

    /** The total send deadline elapsed before the hub accepted the notification. */
    TIMEOUT,

    /** The send was accepted but its result can no longer be observed (fire-and-forget). */
    DROPPED
}
