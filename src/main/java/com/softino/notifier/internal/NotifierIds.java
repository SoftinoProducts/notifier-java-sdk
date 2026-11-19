package com.softino.notifier.internal;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Identifier helpers for the asynchronous send path.
 *
 * <p>{@link #newIdempotencyKey()} deliberately avoids {@link UUID#randomUUID()}: that method
 * goes through a synchronized {@code SecureRandom} on some JDKs, and the SDK must not take a
 * lock on the caller's thread. Idempotency keys only need uniqueness, not cryptographic
 * strength; 122 random bits from {@link ThreadLocalRandom} are plenty.</p>
 */
public final class NotifierIds {

    private NotifierIds() {
    }

    /** A random (non-cryptographic) RFC 4122 version-4 UUID string, without synchronization. */
    public static String newIdempotencyKey() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long most = random.nextLong();
        long least = random.nextLong();
        // version 4 and IETF variant, as UUID.randomUUID() would set them.
        most = (most & 0xffffffffffff0fffL) | 0x0000000000004000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least).toString();
    }
}
