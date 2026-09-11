package com.softino.notifier;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.softino.notifier.exception.ApiException;
import com.softino.notifier.kavenegar.KavenegarApi;
import com.softino.notifier.kavenegar.utils.PairValue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end contract test. Spins up a real embedded HTTP server that mimics the
 * Notifier API (the exact paths and JSON the backend returns) and drives the SDK against
 * it — verifying send, bulk, status, provider-message-id status, and error mapping end to end.
 *
 * <p>No external network is used.</p>
 */
public class ContractTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/notifications", this::handleNotifications);
        server.createContext("/v1/notifications/by-provider-message-id", this::handleByProvider);
        server.createContext("/v1/notifications/bulk", this::handleBulk);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleNotifications(HttpExchange ex) throws IOException {
        String body = readBody(ex.getRequestBody());
        lastRequestBody.set(body);

        if ("GET".equals(ex.getRequestMethod())) {
            // History list shape: { items: [ ... ], next_cursor: "..." }
            JsonObject item = new JsonObject();
            item.addProperty("id", UUID.randomUUID().toString());
            item.addProperty("status", "delivered");
            item.addProperty("channel_type", "sms");
            item.addProperty("recipient", "+989120000000");
            item.addProperty("body", "history body");
            JsonObject o = new JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            arr.add(item);
            o.add("items", arr);
            o.addProperty("next_cursor", "cursor-1");
            respond(ex, 200, o);
            return;
        }

        JsonObject o = new JsonObject();
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("status", "queued");
        o.addProperty("channel_type", "sms");
        o.addProperty("recipient", "+989120000000");
        o.addProperty("body", "Your OTP is 1234");
        respond(ex, 202, o);
    }

    private void handleByProvider(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("id", "c33aa8ec-e20c-4aa1-90b8-ada99822c3d0");
        o.addProperty("status", "delivered");
        o.addProperty("channel_type", "sms");
        o.addProperty("recipient", "+989120000000");
        o.addProperty("body", "hello");
        o.addProperty("provider", "kavenegar");
        o.addProperty("provider_message_id", "12345678");
        o.addProperty("sent_at", "2026-01-01T00:00:00Z");
        respond(ex, 200, o);
    }

    private void handleBulk(HttpExchange ex) throws IOException {
        // Recorded like the single-send path so a test can assert what the SDK put on the wire.
        lastRequestBody.set(readBody(ex.getRequestBody()));
        JsonObject o = new JsonObject();
        o.addProperty("batch_id", UUID.randomUUID().toString());
        o.addProperty("count", 2);
        respond(ex, 202, o);
    }

    private static void respond(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
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

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    public void send_roundTrip() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        SendResult r = api.send(ChannelType.SMS, "+989120000000", Content.of("Your OTP is 1234"));
        assertNotNull(r.getId());
        assertEquals("queued", r.getStatus());
        assertEquals("sms", r.getChannelType());
        assertTrue("body should be sent", lastRequestBody.get().contains("\"recipient\":\"+989120000000\""));
    }

    @Test
    public void bulk_roundTrip() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        BulkResult b = api.bulk(ChannelType.SMS, Arrays.asList(
                RecipientMessage.of("+989120000000", Content.of("one")),
                RecipientMessage.of("+989121111111", Content.of("two"))));
        assertNotNull(b.getBatchId());
        assertEquals(2, b.getCount());
    }

    @Test
    public void statusByProviderMessageId_roundTrip() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        StatusResult s = api.statusByProviderMessageId("12345678");
        assertTrue(s.isDelivered());
        assertEquals("kavenegar", s.getProvider());
        assertEquals("12345678", s.getProviderMessageId());
        assertEquals("2026-01-01T00:00:00Z", s.getSentAt());
    }

    @Test
    public void kavenegarFacade_statusByLong() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        com.softino.notifier.kavenegar.models.StatusResult s = api.status(12345678L);
        assertEquals(com.softino.notifier.kavenegar.enums.MessageStatus.Delivered, s.getStatus());
        assertEquals("تحویل شد", s.getStatusText());      // mapped kavenegar-style statusText
    }

    @Test
    public void businessError_mapsToApiException() throws IOException {
        HttpServer errServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        errServer.createContext("/v1/notifications", exchange -> {
            JsonObject o = new JsonObject();
            o.addProperty("error", "duplicate idempotency key");
            o.addProperty("code", "DUPLICATE_IDEMPOTENCY_KEY");
            respond(exchange, 422, o);
        });
        errServer.start();
        try {
            NotifierApi api = new NotifierApi("test-key", "http://127.0.0.1:" + errServer.getAddress().getPort());
            try {
                api.send(ChannelType.SMS, "+989120000000", Content.of("hi"));
                fail("expected ApiException");
            } catch (ApiException ex) {
                assertEquals("duplicate idempotency key", ex.getMessage());
            }
        } finally {
            errServer.stop(0);
        }
    }

    @Test
    public void sendOptions_templateVarsAreTransmitted() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        SendOptions opts = SendOptions.builder()
                .templateId("otp-template")
                .templateVar("token", "123456")
                .build();
        api.sendTemplate(ChannelType.SMS, "+989120000000", "otp-template",
                Collections.singletonMap("token", "123456"), opts);
        String body = lastRequestBody.get();
        assertTrue(body.contains("\"template_id\":\"otp-template\""));
        assertTrue(body.contains("\"token\":\"123456\""));
    }

    // A rehearsal is the same request plus one field: templates, groups and channels stay as they
    // are, and the flag must actually reach the wire.
    @Test
    public void sendOptions_simulatedIsTransmitted() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        SendOptions opts = SendOptions.builder().simulated(true).build();
        api.sendTemplate(ChannelType.SMS, "+989120000000", "verify",
                Collections.singletonMap("token", "123456"), opts);
        String body = lastRequestBody.get();
        assertTrue("simulated flag should be sent", body.contains("\"simulated\":true"));
        assertTrue("the template must be unchanged", body.contains("\"template_id\":\"verify\""));
    }

    @Test
    public void sendOptions_simulatedIsOmittedWhenFalse() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        api.sendTemplateByName(ChannelType.SMS, "+989120000000", "betaauth",
                Collections.singletonMap("token", "777"),
                SendOptions.builder().locale("fa").build());
        assertFalse("an ordinary send must not carry the flag", lastRequestBody.get().contains("simulated"));
    }

    @Test
    public void bulk_simulatedBatchFlagAndPerMessageOverride() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        RecipientMessage inherits = RecipientMessage.builder()
                .recipient("+989120000001")
                .content(Content.of("rehearsal"))
                .build();
        RecipientMessage overrides = RecipientMessage.builder()
                .recipient("+989120000002")
                .content(Content.of("real"))
                .simulated(false)
                .build();

        api.bulk(ChannelType.SMS, Arrays.asList(inherits, overrides), true);

        String body = lastRequestBody.get();
        assertTrue("batch flag should be sent", body.contains("\"simulated\":true"));
        assertTrue("per-message override should be sent", body.contains("\"simulated\":false"));
    }

    @Test
    public void statusResult_reportsSimulated() throws IOException {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        SendResult r = api.send(ChannelType.SMS, "+989120000000", "hello");
        assertFalse("a normal send reports simulated=false", r.isSimulated());
    }

    @Test
    public void sendTemplateByName_sendsTemplateName() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        api.sendTemplateByName(ChannelType.SMS, "+989120000000", "betaauth",
                Collections.singletonMap("token", "777"));
        String body = lastRequestBody.get();
        assertTrue("should reference template by name", body.contains("\"template_name\":\"betaauth\""));
        assertTrue(body.contains("\"token\":\"777\""));
    }

    // Existing Kavenegar call sites can be put into rehearsal by adding one argument: no
    // migration to the NotifierApi interface is needed.
    @Test
    public void verifyLookup_simulatedOverloadIsTransmitted() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        api.verifyLookup("+989120000000", "123456", "sms-group:verify", true);

        String body = lastRequestBody.get();
        assertTrue("simulated flag should be sent", body.contains("\"simulated\":true"));
        assertTrue("the template must be unchanged", body.contains("\"template_name\":\"verify\""));
        assertTrue("the group shorthand must still apply", body.contains("\"group_name\":\"sms-group\""));
    }

    @Test
    public void verifyLookup_simulatedWithToken2AndToken3() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        api.verifyLookup("+989120000000", "1", "2", "3", "order_notice", true);

        String body = lastRequestBody.get();
        assertTrue(body.contains("\"simulated\":true"));
        assertTrue("positional values must still be sent", body.contains("\"template_params\":[\"1\",\"2\",\"3\"]"));
    }

    @Test
    public void verifyLookup_simulatedWithNamedParams() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        api.verifyLookup("+989120000000", "", "", "", "verify",
                Collections.singletonList(new PairValue("token", "998877")), true);

        String body = lastRequestBody.get();
        assertTrue(body.contains("\"simulated\":true"));
        assertTrue(body.contains("\"token\":\"998877\""));
    }

    @Test
    public void verifyLookup_simulatedFalseIsOmitted() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        api.verifyLookup("+989120000000", "123456", "verify", false);
        assertFalse("an explicit false must not send the flag", lastRequestBody.get().contains("simulated"));
    }

    // The overloads without the flag keep their exact previous behaviour.
    @Test
    public void verifyLookup_withoutFlagIsUnchanged() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        api.verifyLookup("+989120000000", "123456", "verify");

        String body = lastRequestBody.get();
        assertFalse("no flag by default", body.contains("simulated"));
        assertTrue(body.contains("\"template_name\":\"verify\""));
        assertTrue(body.contains("\"template_params\":[\"123456\"]"));
    }

    @Test
    public void listNotifications_returnsPageAndCursor() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        HistoryPage page = api.listNotifications(HistoryQuery.builder().limit(5).status("delivered").build());
        assertNotNull(page);
        assertEquals(1, page.getItems().size());
        assertEquals("delivered", page.getItems().get(0).getStatus());
        assertEquals("cursor-1", page.getNextCursor());
        assertTrue(page.hasNext());
    }

    @Test
    public void sendTemplateByNamePositional_emitsTemplateParams() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        api.sendTemplateByName(ChannelType.SMS, "+989120000000", "betaauth", "123456", "extra");
        String body = lastRequestBody.get();
        assertTrue("should reference template by name", body.contains("\"template_name\":\"betaauth\""));
        assertTrue("positional params should be sent as template_params array",
                body.contains("\"template_params\":[\"123456\",\"extra\"]"));
    }

    @Test
    public void sendTemplateByName_groupTemplate_emitsGroupName() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        api.sendTemplateByName(ChannelType.SMS, "+989120000000", "sms-sale:verify",
                Collections.singletonMap("token", "777"));
        String body = lastRequestBody.get();
        assertTrue("standard by-name send should support 'group:template' shorthand",
                body.contains("\"group_name\":\"sms-sale\""));
        assertTrue("template name should be the part after ':'", body.contains("\"template_name\":\"verify\""));
    }

    @Test
    public void sendTemplateByNamePositional_groupTemplate_emitsGroupNameAndParams() {
        NotifierApi api = new NotifierApi("test-key", baseUrl);
        api.sendTemplateByName(ChannelType.SMS, "+989120000000", "sms-otp:betaauth", "123456");
        String body = lastRequestBody.get();
        assertTrue("positional by-name send should support 'group:template' shorthand",
                body.contains("\"group_name\":\"sms-otp\""));
        assertTrue(body.contains("\"template_name\":\"betaauth\""));
        assertTrue(body.contains("\"template_params\":[\"123456\"]"));
    }

    @Test
    public void verifyLookup_groupTemplate_emitsGroupName() {
        KavenegarApi api = new KavenegarApi("test-key", baseUrl);
        api.verifyLookup("+989120000000", "123456", "sms-sale:verify");
        String body = lastRequestBody.get();
        assertTrue("should reference the group by name", body.contains("\"group_name\":\"sms-sale\""));
        assertTrue("should keep the template name", body.contains("\"template_name\":\"verify\""));
    }
}
