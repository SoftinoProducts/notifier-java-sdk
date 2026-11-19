package com.softino.notifier;

/**
 * Delivery lane for an asynchronous send.
 *
 * <p>The SDK keeps two independent lanes with their own queues, dispatcher threads and
 * in-flight quotas:</p>
 *
 * <ul>
 *   <li>{@link #HIGH} — transactional / OTP traffic. Never batched, never queued behind
 *       {@link #BULK} work, and served by a reserved share of the HTTP concurrency.</li>
 *   <li>{@link #BULK} — everything else (marketing, notifications, receipts).</li>
 * </ul>
 *
 * <p>When {@link SendOptions#getPriority()} is unset, the call site chooses: the Kavenegar
 * {@code verifyLookup*} family defaults to {@link #HIGH}; generic sends default to
 * {@link #BULK}. Set it explicitly when a generic send carries an OTP.</p>
 */
public enum SendPriority {

    /** OTP / transactional lane: reserved capacity, no batching, no starvation by bulk work. */
    HIGH,

    /** Bulk lane: everything that is not time-critical. */
    BULK
}
