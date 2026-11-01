package com.softino.notifier;

import com.softino.notifier.exception.CallbackSignatureException;
import com.softino.notifier.exception.CallbackSignatureException.Reason;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link CallbackVerifier}.
 *
 * <p>The vectors here are the same ones asserted by the Go SDK, the API, and the compatibility
 * test in notifier-api/compat: a receiver that passes these is implementing the contract the
 * service actually sends.</p>
 */
public class CallbackVerifierTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String TIMESTAMP = "1730000000";
    private static final String BODY =
            "{\"notification_id\":\"801e8e60-4686-4cba-8068-e36c88618b16\",\"status\":\"delivered\",\"channel_type\":\"telegram\"}";

    /** HMAC-SHA256 over "<timestamp>.<body>", as the service computes it. */
    private static final String GOLDEN_SIGNATURE =
            "79c471e9051eb5cb40beba0463903244d673cd74a0361ab9114daece83c38114";

    /** The same body signed without the timestamp: the old, broken scheme. */
    private static final String BODY_ONLY_SIGNATURE =
            "6e3cc01bb604f7cd43b6a4b6ec86456ec0853155581b3f70a03e2d9ccc6c3ba6";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static CallbackVerifier at(long epochSecond) {
        return CallbackVerifier.forSecret(SECRET)
                .withClock(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private static String hmacHex(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bytes(secret), "HmacSHA256"));
        mac.update(bytes(timestamp));
        mac.update((byte) '.');
        mac.update(bytes(body));
        StringBuilder out = new StringBuilder();
        for (byte b : mac.doFinal()) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    @Test
    public void acceptsTheGoldenVector() {
        assertTrue(at(1730000000).verify(bytes(BODY), TIMESTAMP, "sha256=" + GOLDEN_SIGNATURE));
    }

    @Test
    public void rejectsABodyOnlySignature() {
        try {
            at(1730000000).verifyOrThrow(bytes(BODY), TIMESTAMP, "sha256=" + BODY_ONLY_SIGNATURE);
            fail("a body-only signature was accepted");
        } catch (CallbackSignatureException e) {
            assertEquals(Reason.INVALID_SIGNATURE, e.getReason());
        }
    }

    @Test
    public void rejectsATamperedBody() {
        CallbackVerifier verifier = at(1730000000);
        assertFalse(verifier.verify(bytes(BODY.replace("delivered", "failed")), TIMESTAMP,
                "sha256=" + GOLDEN_SIGNATURE));
    }

    @Test
    public void rejectsAWrongSecret() {
        CallbackVerifier verifier = CallbackVerifier.forSecret("another-tenant-secret")
                .withClock(Clock.fixed(Instant.ofEpochSecond(1730000000), ZoneOffset.UTC));
        assertFalse(verifier.verify(bytes(BODY), TIMESTAMP, "sha256=" + GOLDEN_SIGNATURE));
    }

    @Test
    public void rejectsAnAlteredTimestamp() {
        // The digest is bound to the timestamp, so moving the timestamp invalidates it.
        assertFalse(at(1730000000).verify(bytes(BODY), "1730000001", "sha256=" + GOLDEN_SIGNATURE));
    }

    @Test
    public void rejectsAMissingOrUnparseableTimestamp() {
        assertReason(Reason.MISSING_TIMESTAMP, null, "sha256=" + GOLDEN_SIGNATURE);
        assertReason(Reason.MISSING_TIMESTAMP, "", "sha256=" + GOLDEN_SIGNATURE);
        assertReason(Reason.MISSING_TIMESTAMP, "yesterday", "sha256=" + GOLDEN_SIGNATURE);
        assertReason(Reason.MISSING_TIMESTAMP, " 1730000000", "sha256=" + GOLDEN_SIGNATURE);
    }

    @Test
    public void rejectsAStaleOrFutureTimestamp() throws Exception {
        String staleSignature = "sha256=" + hmacHex(SECRET, TIMESTAMP, BODY);
        // Replayed an hour later: the digest is still correct, only the age refuses it.
        try {
            at(1730000000 + 3600).verifyOrThrow(bytes(BODY), TIMESTAMP, staleSignature);
            fail("an hour-old callback was accepted");
        } catch (CallbackSignatureException e) {
            assertEquals(Reason.STALE_TIMESTAMP, e.getReason());
        }

        // From the future, beyond the tolerance.
        try {
            at(1730000000 - 600).verifyOrThrow(bytes(BODY), TIMESTAMP, staleSignature);
            fail("a callback from the future was accepted");
        } catch (CallbackSignatureException e) {
            assertEquals(Reason.STALE_TIMESTAMP, e.getReason());
        }
    }

    @Test
    public void acceptsATimestampExactlyAtTheEdge() throws Exception {
        long tolerance = CallbackVerifier.DEFAULT_TOLERANCE_SECONDS;
        String signature = "sha256=" + hmacHex(SECRET, TIMESTAMP, BODY);
        assertTrue(at(1730000000 + tolerance).verify(bytes(BODY), TIMESTAMP, signature));
        assertTrue(at(1730000000 - tolerance).verify(bytes(BODY), TIMESTAMP, signature));
    }

    @Test
    public void rejectsATimestampInMilliseconds() throws Exception {
        String millis = "1730000000000";
        String signature = "sha256=" + hmacHex(SECRET, millis, BODY);
        try {
            at(1730000000).verifyOrThrow(bytes(BODY), millis, signature);
            fail("a millisecond timestamp was accepted");
        } catch (CallbackSignatureException e) {
            assertEquals(Reason.STALE_TIMESTAMP, e.getReason());
        }
    }

    @Test
    public void toleranceZeroDisablesTheWindow() {
        assertTrue(CallbackVerifier.forSecret(SECRET)
                .withTimestampTolerance(0)
                .withClock(Clock.fixed(Instant.ofEpochSecond(1730000000 + 86400), ZoneOffset.UTC))
                .verify(bytes(BODY), TIMESTAMP, "sha256=" + GOLDEN_SIGNATURE));
    }

    @Test
    public void acceptsUppercaseHex() {
        assertTrue(at(1730000000).verify(bytes(BODY), TIMESTAMP,
                "sha256=" + GOLDEN_SIGNATURE.toUpperCase()));
    }

    @Test
    public void rejectsMalformedSignatures() {
        String[] headers = {
                null,
                "",
                "sha256=",
                GOLDEN_SIGNATURE,
                "sha256=" + GOLDEN_SIGNATURE.substring(0, 20),
                "sha256=" + GOLDEN_SIGNATURE + "00",
                "sha256=zzzz",
                "md5=" + GOLDEN_SIGNATURE,
        };
        for (String header : headers) {
            assertReason(Reason.INVALID_SIGNATURE, TIMESTAMP, header);
        }
    }

    @Test
    public void rejectsAMissingBody() {
        try {
            at(1730000000).verifyOrThrow(null, TIMESTAMP, "sha256=" + GOLDEN_SIGNATURE);
            fail("a missing body was accepted");
        } catch (CallbackSignatureException e) {
            assertEquals(Reason.MISSING_BODY, e.getReason());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEmptySecret() {
        CallbackVerifier.forSecret("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANegativeTolerance() {
        CallbackVerifier.forSecret(SECRET).withTimestampTolerance(-1);
    }

    @Test
    public void parsesTheCallbackEvent() {
        CallbackEvent event = CallbackEvent.fromJson(
                "{\"notification_id\":\"801e8e60-4686-4cba-8068-e36c88618b16\","
                        + "\"tenant_id\":\"cc91a6fd-b4c9-4d32-a64e-babd45c58f13\","
                        + "\"status\":\"delivered\",\"channel_type\":\"telegram\","
                        + "\"provider\":\"telegram\",\"provider_message_id\":\"333\","
                        + "\"timestamp\":\"2026-11-01T16:00:03Z\","
                        + "\"unknown_future_field\":\"ignored\"}");

        assertEquals("801e8e60-4686-4cba-8068-e36c88618b16", event.getNotificationId());
        assertEquals("cc91a6fd-b4c9-4d32-a64e-babd45c58f13", event.getTenantId());
        assertEquals("delivered", event.getStatus());
        assertEquals(ChannelType.TELEGRAM, event.getChannelType());
        assertEquals("telegram", event.getProvider());
        assertEquals("333", event.getProviderMessageId());
        assertEquals("2026-11-01T16:00:03Z", event.getTimestamp());
        assertNull(event.getError());
        assertNull(event.getFanoutId());
        assertFalse(event.isSimulated());
        assertTrue(event.isDelivered());
        assertFalse(event.isFailed());
    }

    @Test
    public void parsesAFailedFanoutLeg() {
        CallbackEvent event = CallbackEvent.fromJson(
                "{\"notification_id\":\"n1\",\"tenant_id\":\"t1\",\"status\":\"failed\","
                        + "\"channel_type\":\"sms\",\"fanout_id\":\"f1\","
                        + "\"error\":\"provider rejected the recipient\",\"simulated\":true}");

        assertEquals("f1", event.getFanoutId());
        assertEquals("sms", event.getChannelType().getValue());
        assertEquals("provider rejected the recipient", event.getError());
        assertTrue(event.isSimulated());
        assertTrue(event.isFailed());
        assertFalse(event.isDelivered());
    }

    private static void assertReason(Reason expected, String timestamp, String signature) {
        try {
            at(1730000000).verifyOrThrow(bytes(BODY), timestamp, signature);
            fail("a callback was accepted that should have been refused: " + expected);
        } catch (CallbackSignatureException e) {
            assertEquals(expected, e.getReason());
        }
    }
}
