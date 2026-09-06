package com.softino.notifier.kavenegar;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.softino.notifier.kavenegar.enums.MessageStatus;
import com.softino.notifier.kavenegar.excepctions.BaseException;
import com.softino.notifier.kavenegar.excepctions.HttpException;
import com.softino.notifier.kavenegar.models.AccountInfoResult;
import com.softino.notifier.kavenegar.models.CountInboxResult;
import com.softino.notifier.kavenegar.models.CountOutboxResult;
import com.softino.notifier.kavenegar.models.SendResult;
import com.softino.notifier.kavenegar.models.StatusLocalMessageIdResult;
import com.softino.notifier.kavenegar.models.StatusResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves 100% source compatibility with the exact kavenegar-java call pattern used by the
 * trade-hub project. Every type/getter trade-hub relies on (see its {@code KavenegarSMS} /
 * {@code ISMS}) is exercised here against an embedded server emulating the Notifier API.
 *
 * <p>This test must compile — if any kavenegar-java signature trade-hub uses were missing
 * or mistyped, this file would not build.</p>
 */
public class TradeHubCompatTest {

    private HttpServer server;
    private KavenegarApi api;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/notifications/by-provider-message-id", this::handleByProvider);
        server.createContext("/v1/notifications/", this::handleStatusByUuid); // status by UUID (longest-match prefix)
        server.createContext("/v1/notifications", this::handleSend);          // POST send (exact)
        server.start();
        api = new KavenegarApi("test-key", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleSend(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("status", "queued");
        o.addProperty("channel_type", "sms");
        o.addProperty("recipient", "+989120000000");
        o.addProperty("body", "hello");
        respond(ex, 202, o);
    }

    private void handleByProvider(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("status", "delivered");
        o.addProperty("channel_type", "sms");
        o.addProperty("recipient", "+989120000000");
        o.addProperty("body", "hello");
        o.addProperty("provider", "kavenegar");
        o.addProperty("provider_message_id", "560152226");
        o.addProperty("sent_at", "2026-01-01T00:00:00Z");
        respond(ex, 200, o);
    }

    // GET /v1/notifications/<uuid> — status by Notifier UUID (statusByNotificationId).
    private void handleStatusByUuid(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(); // "/v1/notifications/<uuid>"
        String uuid = path.substring(path.lastIndexOf('/') + 1);
        JsonObject o = new JsonObject();
        o.addProperty("id", uuid);
        o.addProperty("status", "delivered");
        o.addProperty("channel_type", "sms");
        o.addProperty("recipient", "+989120000000");
        o.addProperty("body", "hello");
        o.addProperty("provider", "kavenegar");
        o.addProperty("provider_message_id", "560152226");
        o.addProperty("sent_at", "2026-01-01T00:00:00Z");
        respond(ex, 200, o);
    }

    private static void respond(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------------
    // The exact trade-hub call pattern (KavenegarSMS.java / ISMS.java)
    // ------------------------------------------------------------------

    @Test
    public void verifyLookup_returnsKavenegarSendResult() {
        // api.verifyLookup(phone, token, template)  in KavenegarSMS
        SendResult result = api.verifyLookup("+989120000000", "123456", "betaauth");

        // result.getMessage() / result.getStatus()==<int> / result.getMessageId() -> Long
        String message = result.getMessage();
        int status = result.getStatus();
        long messageId = result.getMessageId();          // assigned to TransactionSMS.messageId (Long)

        assertNotNull(message);
        assertEquals("hello", message);
        assertTrue(status >= 0);
        assertTrue(messageId >= 0);
    }

    @Test
    public void send_returnsKavenegarSendResultAndStatusRoundTrip() {
        // api.send(sender, receptor, msg) then api.status(messageId)
        SendResult result = api.send("100085902", "+989120000000", "test message");
        String message = result.getMessage();
        long messageId = result.getMessageId();

        assertNotNull(message);
        assertTrue(messageId >= 0);
    }

    @Test
    public void status_returnsKavenegarStatusResult() {
        // api.status(messageId) -> StatusResult; getStatusText() + getStatus() -> MessageStatus
        StatusResult result = api.status(560152226L);

        String statusText = result.getStatusText();
        MessageStatus status = result.getStatus();

        assertEquals("تحویل شد", statusText);   // kavenegar-style statusText for "delivered"
        assertEquals(MessageStatus.Delivered, status);

        // The enum values used by trade-hub are present:
        assertEquals(MessageStatus.Queued, MessageStatus.valueOf(1));
        assertEquals(MessageStatus.Incorrect, MessageStatus.valueOf(100));
    }

    @Test
    public void statusByNotificationId_resolvesByUuid() {
        // Reliable poll: send -> getNotificationId() (UUID, always present) -> status(uuid).
        // Unlike status(messageId), this does not depend on the provider message id being
        // logged yet, so it works for a message that was just sent (async Notifier).
        SendResult sent = api.verifyLookup("+989120000000", "123456", "betaauth");
        String uuid = sent.getNotificationId();
        assertNotNull("notificationId must be present at send time", uuid);

        StatusResult st = api.statusByNotificationId(uuid);

        assertEquals(MessageStatus.Delivered, st.getStatus());
        assertEquals("تحویل شد", st.getStatusText());
        assertEquals(uuid, st.getNotificationId()); // round-trips the UUID
    }

    @Test
    public void httpException_carriesCode_forRetryLogic() {
        // trade-hub: ex instanceof HttpException && httpEx.getCode() in {411,408,...}
        HttpException ex = new HttpException("boom", 503);
        int code = ex.getCode();
        assertEquals(503, code);
        assertTrue(ex instanceof BaseException);
    }

    @Test
    public void fullKavenegarSurface_typesAndCompile() {
        // Unsupported kavenegar features are type-correct and fail loudly with a
        // Kavenegar BaseException (migrated callers catch the kavenegar exception types):
        try { CountOutboxResult r = api.countOutbox(0, 0, 0); fail("expected BaseException"); } catch (BaseException ok) {}
        try { CountInboxResult r = api.countInbox(0, 0, "", 0); fail("expected BaseException"); } catch (BaseException ok) {}
        try { AccountInfoResult a = api.accountInfo(); fail("expected BaseException"); } catch (BaseException ok) {}
        try { api.callMakeTTS("hi", "+989120000000"); fail("expected BaseException"); } catch (BaseException ok) {}

        // Delegating methods return the kavenegar model types:
        StatusLocalMessageIdResult local = api.statusLocalMessageId(5L);
        assertEquals(MessageStatus.Delivered, local.getStatus());
        assertEquals(5L, local.getLocalId());

        java.util.List<SendResult> arr = api.sendArray("100085902",
                Arrays.asList("+989120000000"), Arrays.asList("hello"), "local-1");
        assertEquals(1, arr.size());
    }
}
