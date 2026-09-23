package com.softino.notifier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards for the two transport-hygiene findings in this SDK: unencoded path segments, and
 * a base URL that would send the API key in cleartext.
 *
 * <p>Neither test hits the network — they assert on construction and on URL building only.
 */
public class TransportHygieneTest {

    // ------------------------------------------------------------------
    // Base URL: the API key travels in a header on every request, so a
    // plaintext remote target leaks it to anything on the path.
    // ------------------------------------------------------------------

    @Test
    public void httpsBaseUrlIsAccepted() {
        NotifierApi api = new NotifierApi("key", "https://api.example.com");
        assertEquals("https://api.example.com", api.getBaseUrl());
    }

    @Test
    public void plaintextRemoteBaseUrlIsRefused() {
        for (String url : new String[]{
                "http://api.example.com",
                "http://notifier-api.notifier.svc.cluster.local",
                "http://10.0.0.5:8080",
        }) {
            try {
                new NotifierApi("key", url);
                fail("expected " + url + " to be refused: the API key would be sent in cleartext");
            } catch (IllegalArgumentException expected) {
                assertTrue("message should explain the https requirement, got: " + expected.getMessage(),
                        expected.getMessage().contains("https") || expected.getMessage().contains("cleartext"));
            }
        }
    }

    @Test
    public void loopbackPlaintextIsAllowedForLocalTargets() {
        // A local target cannot be eavesdropped from the network, so refusing it would only
        // make tests and sidecar setups awkward.
        assertNotNull(new NotifierApi("key", "http://127.0.0.1:8080"));
        assertNotNull(new NotifierApi("key", "http://localhost:8080"));
        assertNotNull(new NotifierApi("key", "http://[::1]:8080"));
    }

    @Test
    public void explicitOptOutPermitsPlaintext() {
        NotifierApi api = new NotifierApi("key", "http://notifier-api.notifier.svc.cluster.local",
                1000, 1000, 4, true);
        assertEquals("http://notifier-api.notifier.svc.cluster.local", api.getBaseUrl());
    }

    @Test
    public void unsupportedSchemeIsRefused() {
        try {
            new NotifierApi("key", "ftp://api.example.com");
            fail("expected a non-http scheme to be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("http"));
        }
    }

    // ------------------------------------------------------------------
    // Path segments: an unencoded id changes the request shape, and the
    // API key goes out with whatever request results.
    // ------------------------------------------------------------------

    /**
     * A traversal-shaped id must not be able to address a different endpoint. Before the
     * fix the id was concatenated raw, so {@code status("../../v1/channels")} built a URL
     * pointing at the channels endpoint — with the tenant's API key attached.
     */
    @Test
    public void pathTraversalInIdIsEncoded() throws Exception {
        String encoded = invokeEncodePathSegment("../../v1/channels", "id");
        assertTrue("slashes must be encoded, got: " + encoded, !encoded.contains("/"));
        assertTrue("the result should percent-encode dot segments, got: " + encoded,
                encoded.contains("%2F") || encoded.contains("%2E"));
    }

    @Test
    public void queryAndFragmentInIdAreEncoded() throws Exception {
        String withQuery = invokeEncodePathSegment("abc?tenant=victim", "id");
        assertTrue("? must be encoded, got: " + withQuery, !withQuery.contains("?"));

        String withFragment = invokeEncodePathSegment("abc#frag", "id");
        assertTrue("# must be encoded, got: " + withFragment, !withFragment.contains("#"));
    }

    @Test
    public void spaceBecomesPercentTwentyNotPlus() throws Exception {
        // URLEncoder targets form bodies, where a space is '+'. In a path segment a space
        // must be %20, or the server sees a literal plus.
        String encoded = invokeEncodePathSegment("a b", "id");
        assertEquals("a%20b", encoded);
    }

    @Test
    public void newlineInIdIsEncoded() throws Exception {
        String encoded = invokeEncodePathSegment("abc\r\nX-Injected: 1", "id");
        assertTrue("CR/LF must not survive into a URL, got: " + encoded,
                !encoded.contains("\r") && !encoded.contains("\n"));
    }

    @Test
    public void ordinaryIdIsUnchanged() throws Exception {
        // A normal UUID or provider message id must pass through untouched, so the fix
        // cannot break the common case.
        assertEquals("53500192-61c3-4d4a-9ee6-d69248079f23",
                invokeEncodePathSegment("53500192-61c3-4d4a-9ee6-d69248079f23", "id"));
        assertEquals("1234567890", invokeEncodePathSegment("1234567890", "id"));
    }

    /** Reaches the private encoder so the assertion is about behaviour, not a copy of it. */
    private static String invokeEncodePathSegment(String value, String field) throws Exception {
        java.lang.reflect.Method m = NotifierApi.class
                .getDeclaredMethod("encodePathSegment", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value, field);
    }
}
