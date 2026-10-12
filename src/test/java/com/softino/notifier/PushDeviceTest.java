package com.softino.notifier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.softino.notifier.exception.NotifierException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Contract tests for the Web Push device API. An embedded HTTP server mimics the documented
 * paths and JSON ({@code /v1/push/devices}, {@code /v1/push/vapid-public-key}, and the ordinary
 * {@code /v1/notifications} send), so each test can assert both the request the SDK put on the
 * wire and the value object it parsed back.
 *
 * <p>No external network is used.</p>
 */
public class PushDeviceTest {

    private static final String DEVICE_ID = "53500192-61c3-4d4a-9ee6-d69248079f23";
    private static final String OTHER_DEVICE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String VAPID_CHANNEL_ID = "6f1c9a3e-2b4d-4c8a-9f0e-1a2b3c4d5e6f";
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";
    private static final String PUBLIC_KEY = "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U";

    private HttpServer server;
    private String baseUrl;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastRawPath = new AtomicReference<>();
    private final AtomicReference<String> lastRawQuery = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/push/devices", this::handleDevices);
        server.createContext("/v1/push/vapid-public-key", this::handleVapidKey);
        server.createContext("/v1/notifications", this::handleNotifications);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // Stub endpoints
    // ------------------------------------------------------------------

    private void handleDevices(HttpExchange ex) throws IOException {
        record(ex);
        String path = ex.getRequestURI().getRawPath();

        if ("/v1/push/devices".equals(path)) {
            if ("POST".equals(ex.getRequestMethod())) {
                respond(ex, 201, device(DEVICE_ID));
                return;
            }
            JsonArray items = new JsonArray();
            items.add(device(OTHER_DEVICE_ID));
            items.add(device(DEVICE_ID));
            JsonObject page = new JsonObject();
            page.add("items", items);
            page.addProperty("limit", intParam(ex, "limit", 100));
            page.addProperty("offset", intParam(ex, "offset", 0));
            respond(ex, 200, page);
            return;
        }

        // /v1/push/devices/{id}
        if ("DELETE".equals(ex.getRequestMethod())) {
            // The real endpoint answers 204 with an empty body.
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        respond(ex, 200, device(DEVICE_ID));
    }

    private void handleVapidKey(HttpExchange ex) throws IOException {
        record(ex);
        JsonObject o = new JsonObject();
        o.addProperty("channel_id", VAPID_CHANNEL_ID);
        o.addProperty("public_key", PUBLIC_KEY);
        o.addProperty("subject", "mailto:ops@example.com");
        respond(ex, 200, o);
    }

    private void handleNotifications(HttpExchange ex) throws IOException {
        record(ex);
        JsonObject o = new JsonObject();
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("status", "queued");
        o.addProperty("channel_type", "webpush");
        o.addProperty("recipient", DEVICE_ID);
        respond(ex, 202, o);
    }

    private void record(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        lastMethod.set(ex.getRequestMethod());
        lastRawPath.set(ex.getRequestURI().getRawPath());
        lastRawQuery.set(ex.getRequestURI().getRawQuery());
        lastBody.set(readBody(ex.getRequestBody()));
    }

    private static JsonObject device(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("user_ref", "user-1");
        o.addProperty("channel_id", VAPID_CHANNEL_ID);
        o.addProperty("endpoint_host", "fcm.googleapis.com");
        o.addProperty("is_active", true);
        o.addProperty("failure_count", 0);
        o.addProperty("last_success_at", "2026-01-02T00:00:00Z");
        o.addProperty("last_failure_at", "2026-01-03T00:00:00Z");
        o.addProperty("created_at", "2026-01-01T00:00:00Z");
        o.addProperty("updated_at", "2026-01-04T00:00:00Z");
        return o;
    }

    private static void respond(HttpExchange ex, int code, JsonElement body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int intParam(HttpExchange ex, String name, int defaultValue) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return defaultValue;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return Integer.parseInt(pair.substring(eq + 1));
            }
        }
        return defaultValue;
    }

    /** Reads an InputStream fully. Java 8-compatible replacement for InputStream.readAllBytes (Java 9+). */
    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private NotifierApi api() {
        return new NotifierApi("test-key", baseUrl);
    }

    private static PushSubscription subscription() {
        return PushSubscription.of(ENDPOINT, "base64url-p256dh", "base64url-auth");
    }

    private JsonObject sentBody() {
        return JsonParser.parseString(lastBody.get()).getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // Register
    // ------------------------------------------------------------------

    @Test
    public void registerPushDevice_sendsDocumentedBodyAndOmitsBlankFields() {
        PushDevice device = api().registerPushDevice(subscription());

        assertEquals("POST", lastMethod.get());
        assertEquals("/v1/push/devices", lastRawPath.get());
        assertNull("register carries no query string", lastRawQuery.get());

        JsonObject body = sentBody();
        JsonObject sub = body.getAsJsonObject("subscription");
        assertEquals(ENDPOINT, sub.get("endpoint").getAsString());
        assertEquals("base64url-p256dh", sub.getAsJsonObject("keys").get("p256dh").getAsString());
        assertEquals("base64url-auth", sub.getAsJsonObject("keys").get("auth").getAsString());
        assertFalse("expirationTime is omitted when null", sub.has("expirationTime"));
        assertFalse("blank channel_id is omitted", body.has("channel_id"));
        assertFalse("blank user_ref is omitted", body.has("user_ref"));
        assertTrue("the subscription is the only required object", body.has("subscription"));

        assertEquals(DEVICE_ID, device.getId());
        assertEquals("user-1", device.getUserRef());
        assertEquals(VAPID_CHANNEL_ID, device.getChannelId());
        assertEquals("fcm.googleapis.com", device.getEndpointHost());
        assertTrue(device.isActive());
        assertTrue(device.isUsable());
        assertEquals(0, device.getFailureCount());
        assertEquals("2026-01-02T00:00:00Z", device.getLastSuccessAt());
        assertEquals("2026-01-01T00:00:00Z", device.getCreatedAt());
    }

    @Test
    public void registerPushDevice_sendsUserRefChannelAndExpirationTime() {
        Long expires = 1_700_000_000_000L;
        PushSubscription sub = new PushSubscription(ENDPOINT, expires,
                new PushSubscription.PushSubscriptionKeys("k1", "k2"));

        api().registerPushDevice(RegisterPushDeviceRequest.builder()
                .subscription(sub)
                .userRef("user-42")
                .channelId("  " + VAPID_CHANNEL_ID + "  ")
                .build());

        JsonObject body = sentBody();
        assertEquals("user-42", body.get("user_ref").getAsString());
        assertEquals("channel_id is trimmed like the Go SDK does", VAPID_CHANNEL_ID,
                body.get("channel_id").getAsString());
        assertEquals(expires.longValue(), body.getAsJsonObject("subscription").get("expirationTime").getAsLong());
    }

    @Test
    public void registerPushDevice_validationRejectsMissingFieldsWithoutRequest() {
        NotifierApi api = api();
        PushSubscription.PushSubscriptionKeys keys = new PushSubscription.PushSubscriptionKeys("p256dh", "auth");

        assertRejected(api, new PushSubscription(null, null, keys));
        assertRejected(api, new PushSubscription("   ", null, keys));
        assertRejected(api, new PushSubscription(ENDPOINT, null, null));
        assertRejected(api, new PushSubscription(ENDPOINT, null,
                new PushSubscription.PushSubscriptionKeys("", "auth")));
        assertRejected(api, new PushSubscription(ENDPOINT, null,
                new PushSubscription.PushSubscriptionKeys("p256dh", "  ")));
        assertRejected(api, RegisterPushDeviceRequest.builder().build());

        assertEquals("nothing may reach the service", 0, requests.get());
    }

    private static void assertRejected(NotifierApi api, PushSubscription sub) {
        try {
            api.registerPushDevice(sub);
            fail("expected NotifierException for the incomplete subscription");
        } catch (NotifierException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void assertRejected(NotifierApi api, RegisterPushDeviceRequest request) {
        try {
            api.registerPushDevice(request);
            fail("expected NotifierException for a request without a subscription");
        } catch (NotifierException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @Test
    public void listPushDevices_sendsQueryParametersAndParsesPage() {
        PushDevicePage page = api().listPushDevices(new PushDeviceQuery("user-42", 2, 1));

        assertEquals("GET", lastMethod.get());
        assertEquals("/v1/push/devices", lastRawPath.get());
        String query = lastRawQuery.get();
        assertTrue("user_ref should be forwarded, got: " + query, query.contains("user_ref=user-42"));
        assertTrue("limit should be forwarded, got: " + query, query.contains("limit=2"));
        assertTrue("offset should be forwarded, got: " + query, query.contains("offset=1"));

        assertEquals(2, page.getItems().size());
        assertEquals(2, page.getLimit());
        assertEquals(1, page.getOffset());
        assertTrue("a full page may have a next one", page.hasMore());
        assertEquals(OTHER_DEVICE_ID, page.getItems().get(0).getId());
        assertEquals(DEVICE_ID, page.getItems().get(1).getId());
    }

    @Test
    public void listPushDevices_omitsBlankAndZeroFilters() {
        NotifierApi api = api();
        PushDevicePage page = api.listPushDevices(PushDeviceQuery.none());

        assertNull("no query string when nothing is set", lastRawQuery.get());
        assertEquals(100, page.getLimit());
        assertEquals(0, page.getOffset());
        assertFalse("two items on a 100-item page is not more", page.hasMore());

        api.listPushDevices("   ", 0, 0);
        assertNull("blank user_ref and zero limit/offset are omitted", lastRawQuery.get());

        api.listPushDevices(PushDeviceQuery.forUser("user-42"));
        assertTrue("forUser only sends user_ref, got: " + lastRawQuery.get(),
                lastRawQuery.get().contains("user_ref=user-42") && !lastRawQuery.get().contains("limit"));
    }

    // ------------------------------------------------------------------
    // Get / delete
    // ------------------------------------------------------------------

    @Test
    public void getPushDevice_addressesExactlyOneIdPathSegment() {
        NotifierApi api = api();
        PushDevice device = api.getPushDevice(DEVICE_ID);

        assertEquals("GET", lastMethod.get());
        assertEquals("the uuid must stay one path segment", "/v1/push/devices/" + DEVICE_ID, lastRawPath.get());
        assertNull("the id must not leak into a query", lastRawQuery.get());
        assertEquals(DEVICE_ID, device.getId());
        assertEquals("2026-01-02T00:00:00Z", device.getLastSuccessAt());
        assertEquals("2026-01-03T00:00:00Z", device.getLastFailureAt());

        assertEquals(DEVICE_ID, api.pushDevice(DEVICE_ID).getId());   // alias
    }

    @Test
    public void getPushDevice_rejectsIdsThatAreNotDeviceUuidsWithoutRequesting() {
        NotifierApi api = api();
        String[] rejected = {
                "",
                "   ",
                "not-a-uuid",
                "../../v1/notifications",
                ENDPOINT,
        };
        for (String id : rejected) {
            try {
                api.getPushDevice(id);
                fail("expected NotifierException for: " + id);
            } catch (NotifierException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        assertEquals("nothing may reach the service", 0, requests.get());
    }

    @Test
    public void deletePushDevice_toleratesAnEmpty204Body() {
        NotifierApi api = api();
        api.deletePushDevice(DEVICE_ID);   // must not throw on the empty body

        assertEquals("DELETE", lastMethod.get());
        assertEquals("/v1/push/devices/" + DEVICE_ID, lastRawPath.get());
    }

    @Test
    public void deletePushDevice_rejectsIdsThatAreNotDeviceUuidsWithoutRequesting() {
        NotifierApi api = api();
        try {
            api.deletePushDevice("{\"endpoint\":\"" + ENDPOINT + "\"}");
            fail("expected NotifierException");
        } catch (NotifierException expected) {
            assertTrue(expected.getMessage().contains("device id"));
        }
        assertEquals("nothing may reach the service", 0, requests.get());
    }

    // ------------------------------------------------------------------
    // VAPID public key
    // ------------------------------------------------------------------

    @Test
    public void getVapidPublicKey_sendsAndOmitsChannelId() {
        NotifierApi api = api();

        VapidKey key = api.getVapidPublicKey();
        assertEquals("GET", lastMethod.get());
        assertEquals("/v1/push/vapid-public-key", lastRawPath.get());
        assertNull("the default call must not pin a channel", lastRawQuery.get());
        assertEquals(VAPID_CHANNEL_ID, key.getChannelId());
        assertEquals(PUBLIC_KEY, key.getPublicKey());
        assertEquals("mailto:ops@example.com", key.getSubject());

        api.getVapidPublicKey(VAPID_CHANNEL_ID);
        assertTrue("channel_id should be forwarded, got: " + lastRawQuery.get(),
                lastRawQuery.get().contains("channel_id=" + VAPID_CHANNEL_ID));

        api.getVapidPublicKey("   ");
        assertNull("a blank channel id is omitted", lastRawQuery.get());
    }

    // ------------------------------------------------------------------
    // Sending to a device
    // ------------------------------------------------------------------

    @Test
    public void sendToDevice_sendsWebpushChannelWithDeviceRecipient() {
        SendResult r = api().sendToDevice(DEVICE_ID, "Order ready", "Your order has shipped.");

        assertEquals("POST", lastMethod.get());
        assertEquals("/v1/notifications", lastRawPath.get());
        JsonObject body = sentBody();
        assertEquals("webpush", body.get("channel_type").getAsString());
        assertEquals("the subscription is addressed by device id", DEVICE_ID, body.get("recipient").getAsString());
        assertEquals("Order ready", body.get("subject").getAsString());
        assertEquals("Your order has shipped.", body.get("body").getAsString());

        assertEquals("queued", r.getStatus());
        assertEquals("webpush", r.getChannelType());
    }

    @Test
    public void sendToDevice_withoutSubjectOmitsTheSubject() {
        api().sendToDevice(DEVICE_ID, "just the body");

        JsonObject body = sentBody();
        assertEquals("webpush", body.get("channel_type").getAsString());
        assertEquals(DEVICE_ID, body.get("recipient").getAsString());
        assertFalse(body.has("subject"));
        assertEquals("just the body", body.get("body").getAsString());
    }

    @Test
    public void sendToDevice_rejectsASubscriptionInsteadOfADeviceId() {
        NotifierApi api = api();
        try {
            api.sendToDevice("{\"endpoint\":\"" + ENDPOINT + "\"}", "hi");
            fail("expected NotifierException");
        } catch (NotifierException expected) {
            assertTrue("the message must say what to pass instead, got: " + expected.getMessage(),
                    expected.getMessage().contains("device id"));
        }
        assertEquals("nothing may reach the service", 0, requests.get());
    }

    @Test
    public void sendTemplateToDevice_sendsWebpushTemplateAndPositionalParams() {
        api().sendTemplateToDevice(DEVICE_ID, "order-ready", "12345", "Tehran");

        JsonObject body = sentBody();
        assertEquals("webpush", body.get("channel_type").getAsString());
        assertEquals(DEVICE_ID, body.get("recipient").getAsString());
        assertEquals("order-ready", body.get("template_name").getAsString());
        assertEquals(2, body.getAsJsonArray("template_params").size());
        assertEquals("12345", body.getAsJsonArray("template_params").get(0).getAsString());
        assertEquals("Tehran", body.getAsJsonArray("template_params").get(1).getAsString());
    }

    // ------------------------------------------------------------------
    // Model-level behaviour (no network)
    // ------------------------------------------------------------------

    @Test
    public void deviceRecipient_rejectsASubscriptionJsonAndABareEndpoint() {
        assertEquals(DEVICE_ID, NotifierApi.deviceRecipient(DEVICE_ID));

        String[] rejected = {
                "{\"endpoint\":\"" + ENDPOINT + "\",\"keys\":{}}",
                ENDPOINT,
                "",
                "not-a-uuid",
        };
        for (String value : rejected) {
            try {
                NotifierApi.deviceRecipient(value);
                fail("expected NotifierException for: " + value);
            } catch (NotifierException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void deviceRecipient_explainsTheSubscriptionConfusion() {
        try {
            NotifierApi.deviceRecipient("{\"endpoint\":\"" + ENDPOINT + "\"}");
            fail("expected NotifierException");
        } catch (NotifierException expected) {
            assertTrue("the message must name the fix, got: " + expected.getMessage(),
                    expected.getMessage().contains("registerPushDevice"));
            assertTrue(expected.getMessage().contains("device id"));
        }
        try {
            NotifierApi.deviceRecipient(ENDPOINT);
            fail("expected NotifierException");
        } catch (NotifierException expected) {
            assertTrue("a bare endpoint is the same mistake, got: " + expected.getMessage(),
                    expected.getMessage().contains("device id"));
        }
    }

    @Test
    public void pushDeviceParsesOmittedOptionalFields() {
        JsonObject o = new JsonObject();
        o.addProperty("id", DEVICE_ID);
        o.addProperty("user_ref", "");
        o.addProperty("endpoint_host", "fcm.googleapis.com");
        o.addProperty("is_active", false);
        o.addProperty("failure_count", 4);
        o.addProperty("created_at", "2026-01-01T00:00:00Z");
        o.addProperty("updated_at", "2026-01-01T00:00:00Z");

        PushDevice d = PushDevice.from(o);
        assertNull("an omitted channel_id stays null", d.getChannelId());
        assertNull(d.getLastSuccessAt());
        assertNull(d.getLastFailureAt());
        assertEquals(4, d.getFailureCount());
        assertFalse("a deactivated device is not usable", d.isUsable());
    }

    @Test
    public void pushDevicePageDefaultsToAnEmptyPage() {
        PushDevicePage p = PushDevicePage.from(new JsonObject());
        assertTrue(p.getItems().isEmpty());
        assertEquals(0, p.getLimit());
        assertEquals(0, p.getOffset());
        assertFalse(p.hasMore());
    }

    @Test
    public void pushSubscriptionToStringDoesNotLeakTheEndpoint() {
        assertFalse("the endpoint is a capability and must not reach logs",
                PushSubscription.of(ENDPOINT, "k", "a").toString().contains("fcm.googleapis.com"));
    }
}
