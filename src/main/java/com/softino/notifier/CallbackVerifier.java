package com.softino.notifier;

import com.softino.notifier.exception.CallbackSignatureException;
import com.softino.notifier.exception.CallbackSignatureException.Reason;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;

/**
 * Authenticates a delivery callback.
 *
 * <p>Notifier signs {@code "<timestamp>.<raw body>"} with HMAC-SHA256 using the tenant's
 * webhook signing secret and sends the digest in {@link #SIGNATURE_HEADER} as
 * {@code sha256=<hex>}, with the same Unix-seconds timestamp in {@link #TIMESTAMP_HEADER}.
 * The digest covers the exact body bytes, so verify before parsing the JSON: decoding and
 * re-encoding changes whitespace and field order, and the signature will never match.</p>
 *
 * <pre>{@code
 * private static final CallbackVerifier VERIFIER =
 *         CallbackVerifier.forSecret(System.getenv("NOTIFIER_WEBHOOK_SECRET"));
 *
 * protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
 *     byte[] raw = readAll(req.getInputStream());   // before any JSON parsing
 *     try {
 *         VERIFIER.verifyOrThrow(raw,
 *                 req.getHeader(CallbackVerifier.TIMESTAMP_HEADER),
 *                 req.getHeader(CallbackVerifier.SIGNATURE_HEADER));
 *     } catch (CallbackSignatureException e) {
 *         res.sendError(401, e.getMessage());
 *         return;
 *     }
 *     CallbackEvent event = CallbackEvent.fromJson(new String(raw, StandardCharsets.UTF_8));
 *     res.setStatus(204);
 * }
 * }</pre>
 *
 * <p>A verifier is immutable and safe for concurrent use. {@link #withTimestampTolerance}
 * and {@link #withClock} return a new instance rather than mutating this one.</p>
 */
public final class CallbackVerifier {

    /** Carries the digest as {@code sha256=<lowercase hex>}. */
    public static final String SIGNATURE_HEADER = "X-Notifier-Signature";

    /** Carries the Unix-seconds timestamp that was signed together with the body. */
    public static final String TIMESTAMP_HEADER = "X-Notifier-Timestamp";

    /**
     * How far a callback's timestamp may be from the receiver's clock. It absorbs ordinary
     * clock skew and nothing more.
     */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300L;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final byte[] secret;
    private final long toleranceSeconds;
    private final Clock clock;

    private CallbackVerifier(byte[] secret, long toleranceSeconds, Clock clock) {
        this.secret = secret;
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    /**
     * Builds a verifier for the tenant's webhook signing secret.
     *
     * @throws IllegalArgumentException when the secret is empty. Accepting an empty secret
     *         would silently trust every request, so it fails closed instead.
     */
    public static CallbackVerifier forSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("webhook signing secret is required");
        }
        return new CallbackVerifier(secret.getBytes(StandardCharsets.UTF_8),
                DEFAULT_TOLERANCE_SECONDS, Clock.systemUTC());
    }

    /**
     * Returns a verifier that accepts a timestamp this many seconds from now.
     *
     * <p>{@code 0} disables the window. Do that only in a process that already rejects
     * repeats by other means (an idempotency key on the effect, for instance); with the
     * window off, a captured callback stays valid forever.</p>
     *
     * @throws IllegalArgumentException when {@code seconds} is negative
     */
    public CallbackVerifier withTimestampTolerance(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("tolerance must not be negative: " + seconds);
        }
        return new CallbackVerifier(secret, seconds, clock);
    }

    /** Returns a verifier that reads "now" from {@code clock}; useful in tests. */
    public CallbackVerifier withClock(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        return new CallbackVerifier(secret, toleranceSeconds, clock);
    }

    /**
     * Reports whether a callback is authentic, without saying why it is not. Use
     * {@link #verifyOrThrow} when the reason matters for logging.
     */
    public boolean verify(byte[] body, String timestampHeader, String signatureHeader) {
        try {
            verifyOrThrow(body, timestampHeader, signatureHeader);
            return true;
        } catch (CallbackSignatureException e) {
            return false;
        }
    }

    /**
     * Authenticates a callback, throwing when it cannot be trusted.
     *
     * @param body            the raw request body, exactly as it arrived
     * @param timestampHeader the {@code X-Notifier-Timestamp} header
     * @param signatureHeader the {@code X-Notifier-Signature} header
     * @throws CallbackSignatureException when the request is not an authentic, fresh callback
     */
    public void verifyOrThrow(byte[] body, String timestampHeader, String signatureHeader) {
        if (body == null) {
            throw new CallbackSignatureException(Reason.MISSING_BODY);
        }

        long timestamp = parseTimestamp(timestampHeader);
        checkFreshness(timestamp);

        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            throw new CallbackSignatureException(Reason.INVALID_SIGNATURE,
                    "invalid callback signature: expected \"" + SIGNATURE_PREFIX + "<hex>\"");
        }
        byte[] provided = decodeHex(signatureHeader.substring(SIGNATURE_PREFIX.length()));
        byte[] expected = digest(timestampHeader, body);
        // Constant time for equal-length inputs; the length is not a secret.
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new CallbackSignatureException(Reason.INVALID_SIGNATURE);
        }
    }

    private static long parseTimestamp(String header) {
        if (header == null || header.isEmpty()) {
            throw new CallbackSignatureException(Reason.MISSING_TIMESTAMP);
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new CallbackSignatureException(Reason.MISSING_TIMESTAMP,
                    "callback timestamp is not unix seconds: \"" + header + "\"");
        }
    }

    private void checkFreshness(long timestamp) {
        if (toleranceSeconds == 0) {
            return;
        }
        long now = Instant.now(clock).getEpochSecond();
        // Compare in whole seconds and guard the subtraction: a timestamp in milliseconds
        // parses as a number that is so far away that an unchecked subtraction overflows,
        // and an overflowed difference compares as "close".
        long skew;
        if (now >= timestamp) {
            skew = now - timestamp;
        } else {
            skew = timestamp - now;
        }
        if (skew < 0) {
            skew = Long.MAX_VALUE;
        }
        if (skew > toleranceSeconds) {
            throw new CallbackSignatureException(Reason.STALE_TIMESTAMP,
                    "stale callback timestamp: " + timestamp + " is " + skew
                            + "s from the receiver's clock (tolerance " + toleranceSeconds + "s)");
        }
    }

    private byte[] digest(String timestampHeader, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            mac.update(timestampHeader.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every Java platform, so this cannot happen on a
            // supported runtime. Failing loudly beats returning a digest that is not one.
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] decodeHex(String hex) {
        int length = hex.length();
        if (length == 0 || length % 2 != 0) {
            throw new CallbackSignatureException(Reason.INVALID_SIGNATURE);
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = hexValue(hex.charAt(i));
            int low = hexValue(hex.charAt(i + 1));
            if (high < 0 || low < 0) {
                throw new CallbackSignatureException(Reason.INVALID_SIGNATURE);
            }
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /** Returns the numeric value of a hex digit, or -1 when the character is not hex. */
    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
